package com.yunjihai.rag.infrastructure.postgres;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

class FlywayAutoMigrationIntegrationTest {
    @Test void freshLocalDatabaseIsMigratedBeforeRuntimeUse() throws Exception {
        try (var postgres=new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("rag_auto_test").withUsername("migration_owner").withPassword("test_only")) {
            postgres.start();
            try(var connection=java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),"migration_owner","test_only");
                var statement=connection.createStatement()) {
                statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
                statement.execute("ALTER DATABASE rag_auto_test SET rag.runtime_role = 'rag_runtime_test'");
            }
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,FlywayAutoConfiguration.class))
                    .withPropertyValues("spring.datasource.url="+postgres.getJdbcUrl(),
                            "spring.datasource.username=rag_runtime_test", "spring.datasource.password=runtime_test_only",
                            "spring.flyway.enabled=true", "spring.flyway.url="+postgres.getJdbcUrl(),
                            "spring.flyway.user=migration_owner", "spring.flyway.password=test_only")
                    .run(context->{
                        assertThat(context).hasNotFailed();
                        var jdbc=new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
                        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_role_assignment",Long.class)).isZero();
                    });
        }
    }
}
