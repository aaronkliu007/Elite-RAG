package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.qa.QaActorRateLimiter;
import com.yunjihai.rag.qa.QaRateLimitExceededException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public class JdbcQaActorRateLimiter implements QaActorRateLimiter {
    private final JdbcClient jdbc;
    private final Duration window;
    private final long windowMillis;
    private final int maxRequests;

    public JdbcQaActorRateLimiter(JdbcClient jdbc, Duration window, int maxRequests) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.window=Objects.requireNonNull(window);
        if(window.isZero()||window.isNegative())throw new IllegalArgumentException("window must be positive");
        this.windowMillis=window.toMillis();
        if(windowMillis<1)throw new IllegalArgumentException("window must be at least one millisecond");
        if(maxRequests<1)throw new IllegalArgumentException("maxRequests must be positive");
        this.maxRequests=maxRequests;
    }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void acquire(UUID actorId) {
        Objects.requireNonNull(actorId);
        try {
            WindowState state=jdbc.sql("""
                    INSERT INTO qa_actor_rate_limit(
                        actor_id,window_started_at,request_count,updated_at)
                    VALUES (:actorId,clock_timestamp(),1,clock_timestamp())
                    ON CONFLICT (actor_id) DO UPDATE SET
                        (request_count,window_started_at,updated_at)=(
                            WITH request_time AS MATERIALIZED (SELECT clock_timestamp() AS at)
                            SELECT
                                CASE WHEN qa_actor_rate_limit.window_started_at>request_time.at
                                       OR qa_actor_rate_limit.window_started_at<=request_time.at
                                           - (:windowMillis * interval '1 millisecond')
                                     THEN 1 ELSE qa_actor_rate_limit.request_count+1 END,
                                CASE WHEN qa_actor_rate_limit.window_started_at>request_time.at
                                       OR qa_actor_rate_limit.window_started_at<=request_time.at
                                           - (:windowMillis * interval '1 millisecond')
                                     THEN request_time.at ELSE qa_actor_rate_limit.window_started_at END,
                                request_time.at
                            FROM request_time
                        )
                    RETURNING request_count,window_started_at,updated_at AS request_time
                    """)
                    .param("actorId",actorId)
                    .param("windowMillis",windowMillis)
                    .query((row,number)->new WindowState(
                            row.getInt("request_count"),
                            row.getObject("window_started_at",OffsetDateTime.class).toInstant(),
                            row.getObject("request_time",OffsetDateTime.class).toInstant()))
                    .single();
            if(state.requestCount()>maxRequests) {
                Duration remaining=Duration.between(state.requestTime(),state.windowStartedAt().plus(window));
                long retryAfter=remaining.getSeconds()+(remaining.getNano()>0?1:0);
                throw new QaRateLimitExceededException(Math.max(1,retryAfter));
            }
        } catch(DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private record WindowState(int requestCount,Instant windowStartedAt,Instant requestTime) { }
}
