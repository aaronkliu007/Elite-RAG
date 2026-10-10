package com.yunjihai.rag.infrastructure.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

public final class OpenAiCompatibleClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    private final ApprovedModelGatewayProperties properties;
    private final ObjectMapper json;
    private final ModelUsagePolicy usagePolicy;
    private final OkHttpClient http;

    public OpenAiCompatibleClient(
            ApprovedModelGatewayProperties properties,
            ObjectMapper json,
            ModelUsagePolicy usagePolicy) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.usagePolicy = Objects.requireNonNull(usagePolicy, "usagePolicy must not be null");
        this.http = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(properties.connectTimeout())
                .readTimeout(properties.responseTimeout())
                .writeTimeout(properties.responseTimeout())
                .callTimeout(properties.totalTimeout())
                .build();
    }

    public GatewayResponse post(ModelEndpoint endpoint, JsonNode payload) {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ModelGatewayException(ModelGatewayException.Kind.POLICY_VIOLATION);
        }
        int inputTokenUpperBound = conservativeInputTokenUpperBound(body);
        if (inputTokenUpperBound > properties.maxInputTokens()) {
            throw new ModelGatewayException(ModelGatewayException.Kind.POLICY_VIOLATION);
        }
        int outputReservation = endpoint == ModelEndpoint.CHAT_COMPLETIONS
                ? properties.maxOutputTokens()
                : 0;
        int reservedTokens;
        try {
            reservedTokens = Math.addExact(inputTokenUpperBound, outputReservation);
        } catch (ArithmeticException exception) {
            throw new ModelGatewayException(ModelGatewayException.Kind.POLICY_VIOLATION);
        }
        try (var permit = usagePolicy.acquire(reservedTokens)) {
            var response = executeWithSingleRetry(endpoint, body);
            if (response.reportedTokens() >= 0) {
                long maximumReported = (long) properties.maxInputTokens() + outputReservation;
                if (response.reportedTokens() > maximumReported) throw new ModelGatewayException(
                        ModelGatewayException.Kind.INVALID_RESPONSE);
                permit.complete(response.reportedTokens());
            }
            return response;
        }
    }

    private GatewayResponse executeWithSingleRetry(ModelEndpoint endpoint, String body) {
        String requestId = UUID.randomUUID().toString();
        long deadlineNanos;
        try {
            deadlineNanos = Math.addExact(System.nanoTime(), properties.totalTimeout().toNanos());
        } catch (ArithmeticException exception) {
            throw new ModelGatewayException(ModelGatewayException.Kind.POLICY_VIOLATION);
        }
        ModelGatewayException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return execute(endpoint, body, requestId, deadlineNanos);
            } catch (ModelGatewayException exception) {
                last = exception;
                if (!isTransient(exception.kind()) || attempt == 1) throw exception;
            }
        }
        throw last == null
                ? new ModelGatewayException(ModelGatewayException.Kind.UNAVAILABLE)
                : last;
    }

    private GatewayResponse execute(
            ModelEndpoint endpoint, String body, String localRequestId, long deadlineNanos) {
        long started = System.nanoTime();
        var request = new Request.Builder()
                .url(properties.endpointUri(endpoint).toString())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Accept", "application/json")
                .header("X-Request-Id", localRequestId)
                .post(RequestBody.create(body, JSON_MEDIA_TYPE))
                .build();
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) throw new ModelGatewayException(ModelGatewayException.Kind.TIMEOUT);
        var call = http.newCall(request);
        call.timeout().timeout(remainingNanos, TimeUnit.NANOSECONDS);
        try (Response response = call.execute()) {
            Duration latency = Duration.ofNanos(System.nanoTime() - started);
            int status = response.code();
            String providerRequestId = safeRequestId(response.header("x-request-id"), localRequestId);
            if (status >= 300 && status < 400) {
                log(endpoint, localRequestId, status, latency, 0);
                throw new ModelGatewayException(ModelGatewayException.Kind.POLICY_VIOLATION);
            }
            if (status == 401 || status == 403) {
                log(endpoint, localRequestId, status, latency, 0);
                throw new ModelGatewayException(ModelGatewayException.Kind.AUTHENTICATION);
            }
            if (status == 408) {
                log(endpoint, localRequestId, status, latency, 0);
                throw new ModelGatewayException(ModelGatewayException.Kind.TIMEOUT);
            }
            if (status == 429) {
                log(endpoint, localRequestId, status, latency, 0);
                throw new ModelGatewayException(ModelGatewayException.Kind.RATE_LIMITED);
            }
            if (status >= 500) {
                log(endpoint, localRequestId, status, latency, 0);
                throw new ModelGatewayException(ModelGatewayException.Kind.UNAVAILABLE);
            }
            if (status < 200 || status >= 300 || response.body() == null
                    || response.body().contentLength() > MAX_RESPONSE_BYTES) {
                log(endpoint, localRequestId, status, latency, 0);
                throw new ModelGatewayException(ModelGatewayException.Kind.INVALID_RESPONSE);
            }
            byte[] responseBytes;
            try {
                responseBytes = response.body().byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            } catch (InterruptedIOException exception) {
                throw new ModelGatewayException(ModelGatewayException.Kind.TIMEOUT);
            } catch (IOException exception) {
                throw new ModelGatewayException(ModelGatewayException.Kind.UNAVAILABLE);
            }
            if (responseBytes.length > MAX_RESPONSE_BYTES) throw new ModelGatewayException(
                    ModelGatewayException.Kind.INVALID_RESPONSE);
            JsonNode parsed;
            try {
                parsed = json.readTree(responseBytes);
            } catch (IOException | RuntimeException exception) {
                throw new ModelGatewayException(ModelGatewayException.Kind.INVALID_RESPONSE);
            }
            if (parsed == null || !parsed.isObject()) {
                throw new ModelGatewayException(ModelGatewayException.Kind.INVALID_RESPONSE);
            }
            OptionalInt reportedTokens = usageTokens(parsed);
            log(endpoint, localRequestId, status, latency, reportedTokens.orElse(0));
            return new GatewayResponse(parsed, providerRequestId, latency, reportedTokens.orElse(-1));
        } catch (InterruptedIOException exception) {
            throw new ModelGatewayException(ModelGatewayException.Kind.TIMEOUT);
        } catch (IOException exception) {
            throw new ModelGatewayException(ModelGatewayException.Kind.UNAVAILABLE);
        }
    }

    private static boolean isTransient(ModelGatewayException.Kind kind) {
        return kind == ModelGatewayException.Kind.RATE_LIMITED
                || kind == ModelGatewayException.Kind.TIMEOUT
                || kind == ModelGatewayException.Kind.UNAVAILABLE;
    }

    private static int conservativeInputTokenUpperBound(String body) {
        int byteLength = body.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1, byteLength);
    }

    private static OptionalInt usageTokens(JsonNode body) {
        JsonNode usage = body.path("usage");
        if (!usage.isObject()) return OptionalInt.empty();
        JsonNode totalNode = usage.get("total_tokens");
        if (isNonNegativeInt(totalNode)) return OptionalInt.of(totalNode.intValue());
        JsonNode inputNode = usage.get("prompt_tokens");
        JsonNode outputNode = usage.get("completion_tokens");
        if (!isNonNegativeInt(inputNode) || !isNonNegativeInt(outputNode)) return OptionalInt.empty();
        long total = (long) inputNode.intValue() + outputNode.intValue();
        return total > Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of((int) total);
    }

    private static boolean isNonNegativeInt(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0;
    }

    private static String safeRequestId(String candidate, String fallback) {
        if (candidate != null && candidate.matches("[A-Za-z0-9._:-]{1,128}")) return candidate;
        return fallback;
    }

    private static void log(
            ModelEndpoint endpoint, String requestId, int status, Duration latency, int tokens) {
        LOG.info("model_request requestId={} endpoint={} status={} durationMs={} tokens={}",
                requestId, endpoint.identifier(), status, latency.toMillis(), tokens);
    }

    @Override
    public void close() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }

    public record GatewayResponse(
            JsonNode body, String providerRequestId, Duration latency, int reportedTokens) {
        public GatewayResponse {
            Objects.requireNonNull(body, "body must not be null");
            Objects.requireNonNull(providerRequestId, "providerRequestId must not be null");
            Objects.requireNonNull(latency, "latency must not be null");
            if (reportedTokens < -1) throw new IllegalArgumentException("reported tokens are invalid");
        }

        @Override
        public String toString() {
            return "GatewayResponse[providerRequestId=" + providerRequestId
                    + ", latency=" + latency
                    + ", reportedTokens=" + reportedTokens
                    + ", body=<redacted>]";
        }
    }
}
