package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.identity.local.AuthenticationRejectedException;
import com.yunjihai.rag.identity.local.LoginSourceRateLimiter;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;

public final class JdbcLoginSourceRateLimiter implements LoginSourceRateLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration BLOCK_DURATION = Duration.ofMinutes(15);
    private static final int MAX_FAILURES = 20;

    private final JdbcClient jdbc;
    private final byte[] hmacKey;

    public JdbcLoginSourceRateLimiter(JdbcClient jdbc, byte[] hmacKey) {
        this.jdbc = Objects.requireNonNull(jdbc);
        Objects.requireNonNull(hmacKey);
        if (hmacKey.length < 32) {
            throw new IllegalArgumentException("登录来源 HMAC 密钥至少需要 32 字节");
        }
        this.hmacKey = hmacKey.clone();
    }

    @Override
    public void assertAllowed(String sourceIp, Instant at) {
        try {
            boolean blocked = jdbc.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM login_source_attempt
                                WHERE source_hash = :sourceHash AND blocked_until > :at)
                            """)
                    .param("sourceHash", sourceHash(sourceIp))
                    .param("at", utc(at))
                    .query(Boolean.class)
                    .single();
            if (blocked) {
                throw new AuthenticationRejectedException();
            }
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public void recordFailure(String sourceIp, Instant at) {
        try {
            jdbc.sql("""
                            INSERT INTO login_source_attempt
                                (source_hash, failed_attempts, window_started_at, blocked_until, updated_at)
                            VALUES (:sourceHash, 1, :at, NULL, :at)
                            ON CONFLICT (source_hash) DO UPDATE SET
                                failed_attempts = CASE
                                    WHEN login_source_attempt.window_started_at <= :windowCutoff
                                         OR login_source_attempt.blocked_until <= :at THEN 1
                                    ELSE login_source_attempt.failed_attempts + 1
                                END,
                                window_started_at = CASE
                                    WHEN login_source_attempt.window_started_at <= :windowCutoff
                                         OR login_source_attempt.blocked_until <= :at THEN :at
                                    ELSE login_source_attempt.window_started_at
                                END,
                                blocked_until = CASE
                                    WHEN login_source_attempt.window_started_at <= :windowCutoff
                                         OR login_source_attempt.blocked_until <= :at THEN NULL
                                    WHEN login_source_attempt.blocked_until > :at
                                        THEN login_source_attempt.blocked_until
                                    WHEN login_source_attempt.failed_attempts + 1 >= :maxFailures
                                        THEN :blockedUntil
                                    ELSE NULL
                                END,
                                updated_at = :at
                            """)
                    .param("sourceHash", sourceHash(sourceIp))
                    .param("at", utc(at))
                    .param("windowCutoff", utc(at.minus(WINDOW)))
                    .param("blockedUntil", utc(at.plus(BLOCK_DURATION)))
                    .param("maxFailures", MAX_FAILURES)
                    .update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public void reset(String sourceIp) {
        try {
            jdbc.sql("DELETE FROM login_source_attempt WHERE source_hash = :sourceHash")
                    .param("sourceHash", sourceHash(sourceIp))
                    .update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private String sourceHash(String sourceIp) {
        Objects.requireNonNull(sourceIp);
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(hmac.doFinal(sourceIp.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("运行环境不支持 HmacSHA256", impossible);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
