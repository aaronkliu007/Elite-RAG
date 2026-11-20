package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.RagApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InitialPasswordPostgresAcceptanceTest {
    @BeforeAll static void start() { LocalFoundationInfrastructure.start(); }
    @AfterAll static void stop() { LocalFoundationInfrastructure.stop(); }

    @Test
    void bootstrapAndChangePasswordThroughHttpAndRealPostgres() throws Exception {
        var properties = Map.<String,Object>ofEntries(
                Map.entry("server.port", "0"),
                Map.entry("spring.datasource.url", LocalFoundationInfrastructure.jdbcUrl()),
                Map.entry("spring.datasource.username", LocalFoundationInfrastructure.RUNTIME_USER),
                Map.entry("spring.datasource.password", LocalFoundationInfrastructure.RUNTIME_PASSWORD),
                Map.entry("spring.flyway.url", LocalFoundationInfrastructure.jdbcUrl()),
                Map.entry("spring.flyway.user", LocalFoundationInfrastructure.MIGRATION_USER),
                Map.entry("spring.flyway.password", LocalFoundationInfrastructure.MIGRATION_PASSWORD),
                Map.entry("rag.minio.endpoint", LocalFoundationInfrastructure.minioEndpoint()),
                Map.entry("rag.minio.access-key", LocalFoundationInfrastructure.MINIO_APP_USER),
                Map.entry("rag.minio.secret-key", LocalFoundationInfrastructure.MINIO_APP_PASSWORD),
                Map.entry("rag.minio.bucket", LocalFoundationInfrastructure.BUCKET),
                Map.entry("rag.opensearch.enabled", "false"),
                Map.entry("rag.workers.enabled", "false"),
                Map.entry("management.endpoint.health.group.readiness.include", "readinessState,db,minio"));
        try (var context = new SpringApplicationBuilder(RagApplication.class).profiles("local")
                .initializers(app -> app.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("initial-password-acceptance", properties))).run()) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            String base = "http://127.0.0.1:" + port;
            var http = HttpClient.newHttpClient();
            var csrf = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/session/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
            String cookie = cookie(csrf);
            var json = new tools.jackson.databind.ObjectMapper();
            String token = json.readTree(csrf.body()).path("token").asText();
            var login = post(http, base, "/api/v1/session/login", cookie, token, "{\"username\":\"admin\",\"password\":\"admin\"}");
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(json.readTree(login.body()).path("passwordChangeRequired").asBoolean()).isTrue();
            String active = cookie(login);
            var blocked = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/admin/users"))
                    .header("Cookie", active).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(blocked.statusCode()).isEqualTo(403);
            var changed = post(http, base, "/api/v1/session/change-initial-password", active, token,
                    "{\"currentPassword\":\"admin\",\"newPassword\":\"new-admin-password-2026\"}");
            assertThat(changed.statusCode()).isEqualTo(204);
            assertThat(context.getBean(JdbcClient.class).sql("SELECT count(*) FROM audit_event WHERE action = 'PASSWORD_CHANGE'")
                    .query(Long.class).single()).isEqualTo(1);
            var oldSession = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/session/me"))
                    .header("Cookie", active).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(oldSession.statusCode()).isEqualTo(401);
            var fresh = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/session/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
            String freshToken = json.readTree(fresh.body()).path("token").asText();
            var relogin = post(http, base, "/api/v1/session/login", cookie(fresh), freshToken,
                    "{\"username\":\"admin\",\"password\":\"new-admin-password-2026\"}");
            assertThat(relogin.statusCode()).isEqualTo(200);
            assertThat(json.readTree(relogin.body()).path("passwordChangeRequired").asBoolean()).isFalse();
        }
    }

    private static String cookie(HttpResponse<?> response) {
        return response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }
    private static HttpResponse<String> post(HttpClient http, String base, String path, String cookie, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .header("Cookie", cookie).header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
