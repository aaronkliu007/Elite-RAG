package com.yunjihai.rag.infrastructure.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties("rag.ai")
public record ApprovedModelGatewayProperties(
        boolean enabled,
        URI endpoint,
        Set<String> approvedHosts,
        String apiKey,
        String embeddingModel,
        String rerankerModel,
        String chatModel,
        Duration connectTimeout,
        Duration responseTimeout,
        Duration totalTimeout,
        int maxInputTokens,
        int maxOutputTokens,
        int maxConcurrency,
        long dailyDeploymentTokenLimit,
        boolean allowInsecureLoopbackForTests) {

    public ApprovedModelGatewayProperties {
        approvedHosts = approvedHosts == null
                ? Set.of()
                : approvedHosts.stream()
                        .filter(Objects::nonNull)
                        .map(host -> host.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        if (enabled) validate(
                endpoint, approvedHosts, apiKey, embeddingModel, rerankerModel, chatModel,
                connectTimeout, responseTimeout, totalTimeout, maxInputTokens, maxOutputTokens,
                maxConcurrency, dailyDeploymentTokenLimit, allowInsecureLoopbackForTests);
    }

    public URI endpointUri(String path) {
        if (!enabled || java.util.Arrays.stream(ModelEndpoint.values()).noneMatch(value -> value.path().equals(path))) {
            throw configurationFailure();
        }
        return endpoint.resolve(path);
    }

    public URI endpointUri(ModelEndpoint endpointType) {
        Objects.requireNonNull(endpointType, "endpointType must not be null");
        return endpointUri(endpointType.path());
    }

    @Override
    public String toString() {
        return "ApprovedModelGatewayProperties[enabled=" + enabled
                + ", endpoint=" + endpoint
                + ", approvedHosts=" + approvedHosts
                + ", apiKey=<redacted>"
                + ", embeddingModel=" + embeddingModel
                + ", rerankerModel=" + rerankerModel
                + ", chatModel=" + chatModel
                + ", connectTimeout=" + connectTimeout
                + ", responseTimeout=" + responseTimeout
                + ", totalTimeout=" + totalTimeout
                + ", maxInputTokens=" + maxInputTokens
                + ", maxOutputTokens=" + maxOutputTokens
                + ", maxConcurrency=" + maxConcurrency
                + ", dailyDeploymentTokenLimit=" + dailyDeploymentTokenLimit
                + ", allowInsecureLoopbackForTests=" + allowInsecureLoopbackForTests + ']';
    }

    private static void validate(
            URI endpoint,
            Set<String> approvedHosts,
            String apiKey,
            String embeddingModel,
            String rerankerModel,
            String chatModel,
            Duration connectTimeout,
            Duration responseTimeout,
            Duration totalTimeout,
            int maxInputTokens,
            int maxOutputTokens,
            int maxConcurrency,
            long dailyDeploymentTokenLimit,
            boolean allowInsecureLoopbackForTests) {
        try {
            if (endpoint == null || !endpoint.isAbsolute() || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                    || (endpoint.getPath() != null && !endpoint.getPath().isEmpty() && !"/".equals(endpoint.getPath()))) {
                throw configurationFailure();
            }
            String host = endpoint.getHost().toLowerCase(Locale.ROOT);
            if (!approvedHosts.contains(host)) throw configurationFailure();
            boolean secure = "https".equalsIgnoreCase(endpoint.getScheme());
            boolean allowedTestEndpoint = allowInsecureLoopbackForTests
                    && "http".equalsIgnoreCase(endpoint.getScheme())
                    && isLiteralLoopback(host);
            if (!secure && !allowedTestEndpoint) throw configurationFailure();
            requireText(apiKey, 4_096);
            requireText(embeddingModel, 200);
            requireText(rerankerModel, 200);
            requireText(chatModel, 200);
            requirePositive(connectTimeout);
            requirePositive(responseTimeout);
            requirePositive(totalTimeout);
            if (totalTimeout.compareTo(responseTimeout) < 0
                    || maxInputTokens < 1 || maxOutputTokens < 1 || maxConcurrency < 1
                    || dailyDeploymentTokenLimit < Math.addExact(maxInputTokens, maxOutputTokens)) {
                throw configurationFailure();
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw configurationFailure();
        }
    }

    private static void requireText(String value, int maxCodePoints) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxCodePoints) {
            throw configurationFailure();
        }
    }

    private static void requirePositive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) throw configurationFailure();
    }

    private static boolean isLiteralLoopback(String host) {
        if ("localhost".equals(host) || "::1".equals(host)) return true;
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4 || !"127".equals(octets[0])) return false;
        for (String octet : octets) {
            if (!octet.matches("[0-9]{1,3}")) return false;
            int value = Integer.parseInt(octet);
            if (value > 255) return false;
        }
        return true;
    }

    private static IllegalArgumentException configurationFailure() {
        return new IllegalArgumentException("MODEL_GATEWAY_CONFIGURATION_INVALID", null);
    }
}
