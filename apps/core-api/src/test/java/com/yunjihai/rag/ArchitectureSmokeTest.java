package com.yunjihai.rag;

import com.yunjihai.rag.infrastructure.minio.MinioReadinessHealthIndicator;
import com.yunjihai.rag.infrastructure.postgres.JdbcDocumentRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"
})
class ArchitectureSmokeTest {

    @Autowired
    private ApplicationContext context;

    @BeforeAll
    static void isolateTheOrdinaryTestProfile() {
        System.clearProperty("RAG_DB_URL");
        System.clearProperty("RAG_MINIO_ENDPOINT");
    }

    @Test
    void ordinaryTestProfileStartsWithoutExternalInfrastructure() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(JdbcDocumentRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(MinioReadinessHealthIndicator.class)).isEmpty();
    }
}
