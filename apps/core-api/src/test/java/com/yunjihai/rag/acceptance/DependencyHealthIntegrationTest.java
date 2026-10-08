package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.RagApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyHealthIntegrationTest {

    @BeforeAll
    static void startInfrastructure() {
        LocalFoundationInfrastructure.start();
    }

    @AfterAll
    static void stopInfrastructure() {
        LocalFoundationInfrastructure.stop();
    }

    @Test
    void healthyPostgresAndAistorMakeReadinessAndLivenessUp() throws Exception {
        try (var context = startContext(Map.of())) {
            assertContributorUp(context, "db");
            assertContributorUp(context, "minio");
            assertContributorUp(context, "readinessState");
            assertHealth(context, "readiness", 200, "UP");
            assertHealth(context, "liveness", 200, "UP");
        }
    }

    private static void assertContributorUp(ConfigurableApplicationContext context, String name) {
        var contributor = context.getBean(HealthContributorRegistry.class).getContributor(name);
        assertThat(contributor).as("health contributor %s", name).isInstanceOf(HealthIndicator.class);
        assertThat(((HealthIndicator) contributor).health().getStatus())
                .as("health contributor %s", name).isEqualTo(Status.UP);
    }

    @Test
    void unavailablePostgresMakesReadinessDownButLeavesLivenessUp() throws Exception {
        try (var context = startContext(Map.of(
                "spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/unavailable"))) {
            assertHealth(context, "readiness", 503, "DOWN");
            assertHealth(context, "liveness", 200, "UP");
        }
    }

    @Test
    void invalidAistorBucketMakesReadinessDownButLeavesLivenessUp() throws Exception {
        try (var context = startContext(Map.of("rag.minio.bucket", "missing-readiness-bucket"))) {
            assertHealth(context, "readiness", 503, "DOWN");
            assertHealth(context, "liveness", 200, "UP");
        }
    }

    private static ConfigurableApplicationContext startContext(Map<String, Object> overrides) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("server.port", "0");
        properties.put("spring.datasource.url", LocalFoundationInfrastructure.jdbcUrl());
        properties.put("spring.datasource.username", LocalFoundationInfrastructure.RUNTIME_USER);
        properties.put("spring.datasource.password", LocalFoundationInfrastructure.RUNTIME_PASSWORD);
        properties.put("spring.datasource.hikari.connection-timeout", "500");
        properties.put("spring.datasource.hikari.validation-timeout", "500");
        properties.put("spring.datasource.hikari.initialization-fail-timeout", "0");
        properties.put("spring.flyway.url", LocalFoundationInfrastructure.jdbcUrl());
        properties.put("spring.flyway.user", LocalFoundationInfrastructure.MIGRATION_USER);
        properties.put("spring.flyway.password", LocalFoundationInfrastructure.MIGRATION_PASSWORD);
        properties.put("rag.minio.endpoint", LocalFoundationInfrastructure.minioEndpoint());
        properties.put("rag.minio.access-key", LocalFoundationInfrastructure.MINIO_APP_USER);
        properties.put("rag.minio.secret-key", LocalFoundationInfrastructure.MINIO_APP_PASSWORD);
        properties.put("rag.minio.bucket", LocalFoundationInfrastructure.BUCKET);
        properties.put("rag.opensearch.enabled", "false");
        properties.put("rag.workers.enabled", "false");
        properties.put("rag.bootstrap-admin.enabled", "false");
        properties.put("management.endpoint.health.group.readiness.include", "readinessState,db,minio");
        properties.put("spring.autoconfigure.exclude", String.join(",",
                "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
                "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"));
        properties.put("management.endpoint.health.show-details", "never");
        properties.put("logging.level.root", "ERROR");
        properties.putAll(overrides);
        return new SpringApplicationBuilder(RagApplication.class)
                .profiles("local")
                .initializers(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("dependency-health-test", properties)))
                .run();
    }

    private static void assertHealth(
            ConfigurableApplicationContext context,
            String group,
            int expectedHttpStatus,
            String expectedStatus) throws Exception {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/actuator/health/" + group)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("health body: %s", response.body())
                .isEqualTo(expectedHttpStatus);
        assertThat(response.body()).contains("\"status\":\"" + expectedStatus + "\"");
        assertThat(response.body())
                .doesNotContain(LocalFoundationInfrastructure.RUNTIME_PASSWORD)
                .doesNotContain(LocalFoundationInfrastructure.MINIO_APP_PASSWORD)
                .doesNotContain("jdbc:postgresql");
    }
}
