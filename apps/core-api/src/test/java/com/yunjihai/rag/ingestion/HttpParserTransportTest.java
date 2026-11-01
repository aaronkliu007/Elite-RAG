package com.yunjihai.rag.ingestion;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpParserTransportTest {

    @Test
    void streamsMultipartJobAndFileToVersionedEndpoint() throws Exception {
        var requestBody = new AtomicReference<byte[]>();
        var contentType = new AtomicReference<String>();
        var upgrade = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/parse", exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            requestBody.set(exchange.getRequestBody().readAllBytes());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            var response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        var source = Files.createTempFile("http-parser-", ".md");
        try {
            Files.writeString(source, "sensitive-file-bytes");
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var transport = new HttpParserTransport(endpoint, HttpClient.newHttpClient());

            var response = transport.parse(job(Files.size(source)), source);

            var body = new String(requestBody.get(), StandardCharsets.UTF_8);
            assertThat(response).isEqualTo("{\"ok\":true}");
            assertThat(upgrade.get()).isNull();
            assertThat(contentType.get()).startsWith("multipart/form-data; boundary=");
            assertThat(body).contains("name=\"job\"");
            assertThat(body).contains("\"schemaVersion\":\"parser-job-v1\"");
            assertThat(body).contains("name=\"file\"; filename=\"complex-table.md\"");
            assertThat(body).contains("sensitive-file-bytes");
        } finally {
            Files.deleteIfExists(source);
            server.stop(0);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="RAG_TEST_REAL_PARSER_ENDPOINT",matches=".+")
    void communicatesWithRealParserUsingHttp11() throws Exception {
        var source=Files.createTempFile("rag-parser-live-", ".md");
        try {
            Files.writeString(source,"# 虚构解析样本\n\n交付材料包括测试报告和部署清单。",StandardCharsets.UTF_8);
            var digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(source)));
            var job=new ParserJob("parser-job-v1",UUID.randomUUID(),"synthetic.md","text/markdown",
                    Files.size(source),digest,180);
            var transport=new HttpParserTransport(URI.create(System.getenv("RAG_TEST_REAL_PARSER_ENDPOINT")),
                    HttpClient.newHttpClient());
            var response=new tools.jackson.databind.ObjectMapper().readTree(transport.parse(job,source));
            assertThat(response.path("status").asString()).isEqualTo("SUCCEEDED");
            assertThat(response.path("document").path("nodes").size()).isGreaterThan(0);
        } finally { Files.deleteIfExists(source); }
    }

    @Test
    void rejectsParserResponseLargerThanConfiguredLimit() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/parse", exchange -> {
            exchange.getRequestBody().readAllBytes();
            var response = "x".repeat(1025).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        var source = Files.createTempFile("http-parser-", ".md");
        try {
            Files.writeString(source, "safe");
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var transport = new HttpParserTransport(endpoint, HttpClient.newHttpClient(), 1024);

            assertThatThrownBy(() -> transport.parse(job(Files.size(source)), source))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("响应超过大小限制");
        } finally {
            Files.deleteIfExists(source);
            server.stop(0);
        }
    }

    private static ParserJob job(long size) {
        return new ParserJob(
                "parser-job-v1",
                UUID.fromString("00000000-0000-0000-0000-000000000701"),
                "complex-table.md",
                "text/markdown",
                size,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                30);
    }
}
