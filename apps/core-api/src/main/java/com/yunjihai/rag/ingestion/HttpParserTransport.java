package com.yunjihai.rag.ingestion;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

public final class HttpParserTransport implements ParserTransport {

    private static final int DEFAULT_MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final URI endpoint;
    private final HttpClient http;
    private final int maxResponseBytes;
    private final ObjectMapper json = new ObjectMapper();

    public HttpParserTransport(URI endpoint, HttpClient http) {
        this(endpoint, http, DEFAULT_MAX_RESPONSE_BYTES);
    }

    HttpParserTransport(URI endpoint, HttpClient http, int maxResponseBytes) {
        this.endpoint = Objects.requireNonNull(endpoint).resolve("/v1/parse");
        this.http = Objects.requireNonNull(http);
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("解析响应大小限制必须大于零");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public String parse(ParserJob job, Path sourceFile) {
        var boundary = "yunect-parser-" + UUID.randomUUID();
        try {
            var safeFilename = job.filename().replace("\"", "_").replace("\r", "_").replace("\n", "_");
            var jobHeader = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"job\"\r\n"
                    + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                    + json.writeValueAsString(job) + "\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeFilename + "\"\r\n"
                    + "Content-Type: " + job.mediaType() + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            var ending = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            var request = HttpRequest.newBuilder(endpoint)
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(job.timeoutSeconds() + 5L))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.concat(
                            HttpRequest.BodyPublishers.ofByteArray(jobHeader),
                            HttpRequest.BodyPublishers.ofFile(sourceFile),
                            HttpRequest.BodyPublishers.ofByteArray(ending)))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IllegalStateException("解析服务 HTTP 状态异常: " + response.statusCode());
            }
            try (var body = response.body()) {
                var bytes = body.readNBytes(maxResponseBytes + 1);
                if (bytes.length > maxResponseBytes) {
                    throw new IllegalStateException("解析服务响应超过大小限制");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("调用解析服务失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用解析服务被中断", exception);
        }
    }
}
