package com.yunjihai.rag.api.idempotency;

import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;

public class IdempotencyService {
    private static final Duration MINIMUM_RETENTION = Duration.ofHours(24);
    private final IdempotencyStore store;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration retention;

    public IdempotencyService(
            IdempotencyStore store,
            ObjectMapper json,
            Clock clock,
            Duration retention) {
        this.store = Objects.requireNonNull(store);
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
        this.retention = Objects.requireNonNull(retention);
        if (retention.compareTo(MINIMUM_RETENTION) < 0) {
            throw new IllegalArgumentException("idempotency retention must be at least 24 hours");
        }
    }

    @Transactional
    public <T> T execute(
            IdempotencyScope scope,
            String requestHash,
            Class<T> resultType,
            Supplier<T> action) {
        Objects.requireNonNull(scope);
        requireRequestHash(requestHash);
        Objects.requireNonNull(resultType);
        Objects.requireNonNull(action);
        Instant now = clock.instant();
        var claim = store.claim(scope, requestHash, now, now.plus(retention));
        if (claim instanceof IdempotencyStore.Claim.Completed completed) {
            return json.readValue(completed.result().responseBody(), resultType);
        }
        if (claim instanceof IdempotencyStore.Claim.HashMismatch) {
            throw new IdempotencyConflictException(IdempotencyConflictException.Reason.REQUEST_MISMATCH);
        }
        if (claim instanceof IdempotencyStore.Claim.InProgress) {
            throw new IdempotencyConflictException(IdempotencyConflictException.Reason.IN_PROGRESS);
        }
        try {
            T result = action.get();
            String responseBody = json.writeValueAsString(result);
            store.complete(scope, requestHash, new StoredHttpResult(200, responseBody), clock.instant());
            return result;
        } catch (RuntimeException | Error failure) {
            store.release(scope, requestHash);
            throw failure;
        }
    }

    public static String sha256(byte[] requestBytes) {
        Objects.requireNonNull(requestBytes);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(requestBytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void requireRequestHash(String requestHash) {
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be a lowercase SHA-256 hex digest");
        }
    }
}
