package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.qa.QaRateLimitExceededException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcQaActorRateLimiterIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_qa_rate_limit_test").withUsername("migration_owner")
            .withPassword("migration_test_only");
    private static JdbcClient jdbc;
    private static JdbcClient ownerJdbc;
    private static AnnotationConfigApplicationContext context;
    private static com.yunjihai.rag.qa.QaActorRateLimiter transactionalLimiter;
    private static PlatformTransactionManager transactionManager;
    private UUID actor;

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        try(var connection=java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
            var statement=connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_qa_rate_limit_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        ownerJdbc=JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        jdbc=JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),"rag_runtime_test","runtime_test_only"));
        context=new AnnotationConfigApplicationContext(TransactionConfiguration.class);
        transactionalLimiter=context.getBean(com.yunjihai.rag.qa.QaActorRateLimiter.class);
        transactionManager=context.getBean(PlatformTransactionManager.class);
    }

    @AfterAll static void stop(){context.close();POSTGRES.stop();}

    @BeforeEach
    void seed(){
        ownerJdbc.sql("DELETE FROM qa_actor_rate_limit").update();
        jdbc.sql("DELETE FROM app_user").update();
        actor=UUID.randomUUID();
        insertActor(actor,"primary");
    }

    @Test
    void isolatesActorsRejectsBeyondLimitAndResetsAtWindowBoundary(){
        var limiter=new JdbcQaActorRateLimiter(jdbc,Duration.ofMinutes(1),2);
        limiter.acquire(actor);
        limiter.acquire(actor);

        assertThatThrownBy(()->limiter.acquire(actor))
                .isInstanceOf(QaRateLimitExceededException.class)
                .extracting("retryAfterSeconds")
                .satisfies(value->assertThat((Long)value).isBetween(59L,60L));

        UUID other=UUID.randomUUID();
        insertActor(other,"other");
        limiter.acquire(other);
        ownerJdbc.sql("""
                UPDATE qa_actor_rate_limit
                SET window_started_at=clock_timestamp()-interval '61 seconds',
                    updated_at=clock_timestamp()-interval '61 seconds'
                WHERE actor_id=:actor
                """).param("actor",actor).update();
        limiter.acquire(actor);
        assertThat(jdbc.sql("SELECT request_count FROM qa_actor_rate_limit WHERE actor_id=:actor")
                .param("actor",actor).query(Integer.class).single()).isOne();
    }

    @Test
    void atomicallyAdmitsOnlyConfiguredNumberOfConcurrentRequests() throws Exception {
        var limiter=new JdbcQaActorRateLimiter(jdbc,Duration.ofMinutes(1),5);
        var admitted=new AtomicInteger();
        var limited=new AtomicInteger();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var tasks=java.util.stream.IntStream.range(0,20).mapToObj(index->executor.submit(()->{
                try{limiter.acquire(actor);admitted.incrementAndGet();}
                catch(QaRateLimitExceededException expected){limited.incrementAndGet();}
            })).toList();
            for(var task:tasks)task.get();
        }
        assertThat(admitted).hasValue(5);
        assertThat(limited).hasValue(15);
    }

    @Test
    void rejectsInvalidConfiguration(){
        assertThatThrownBy(()->new JdbcQaActorRateLimiter(jdbc,Duration.ZERO,1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new JdbcQaActorRateLimiter(jdbc,Duration.ofMinutes(1),0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void callerClockMovingBackwardDoesNotBreakTheDatabaseConstraint(){
        var limiter=new JdbcQaActorRateLimiter(jdbc,Duration.ofMinutes(1),5);
        limiter.acquire(actor);
        ownerJdbc.sql("""
                UPDATE qa_actor_rate_limit
                SET window_started_at=clock_timestamp()+interval '30 seconds',
                    updated_at=clock_timestamp()+interval '30 seconds'
                WHERE actor_id=:actor
                """).param("actor",actor).update();

        assertThatCode(()->limiter.acquire(actor))
                .doesNotThrowAnyException();
        assertThat(jdbc.sql("SELECT request_count FROM qa_actor_rate_limit WHERE actor_id=:actor")
                .param("actor",actor).query(Integer.class).single()).isOne();
    }

    @Test
    void releasesActorRowLockBeforeTheCallerTransactionFinishes() throws Exception {
        var callerTransaction=new TransactionTemplate(transactionManager);
        var acquired=new CountDownLatch(1);
        var releaseCaller=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var first=executor.submit(()->callerTransaction.executeWithoutResult(ignored->{
                transactionalLimiter.acquire(actor);
                acquired.countDown();
                await(releaseCaller);
            }));
            assertThat(acquired.await(5,TimeUnit.SECONDS)).isTrue();
            var second=executor.submit(()->transactionalLimiter.acquire(actor));
            try {
                second.get(2,TimeUnit.SECONDS);
            } finally {
                releaseCaller.countDown();
                first.get(5,TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void callerRollbackDoesNotRefundAnAdmittedRequest(){
        var callerTransaction=new TransactionTemplate(transactionManager);

        assertThatThrownBy(()->callerTransaction.executeWithoutResult(ignored->{
            transactionalLimiter.acquire(actor);
            throw new IllegalStateException("simulated downstream failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.sql("SELECT request_count FROM qa_actor_rate_limit WHERE actor_id=:actor")
                .param("actor",actor).query(Integer.class).single()).isOne();
    }

    private void insertActor(UUID id,String external){
        jdbc.sql("INSERT INTO app_user(id,external_id,display_name) VALUES (:id,:external,'Reader')")
                .param("id",id).param("external",external+id).update();
    }

    private static void await(CountDownLatch latch){
        try{latch.await();}
        catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted",interrupted);
        }
    }

    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement(proxyTargetClass=true)
    static class TransactionConfiguration {
        @Bean DataSource dataSource(){
            return new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(),"rag_runtime_test","runtime_test_only");
        }
        @Bean JdbcClient jdbcClient(DataSource dataSource){return JdbcClient.create(dataSource);}
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource){
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean com.yunjihai.rag.qa.QaActorRateLimiter qaActorRateLimiter(JdbcClient jdbcClient){
            return new JdbcQaActorRateLimiter(jdbcClient,Duration.ofMinutes(1),5);
        }
    }
}
