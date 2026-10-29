package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.feedback.FeedbackReason;
import com.yunjihai.rag.solution.feedback.SolutionFeedback;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcFeedbackStoreTest {
    @Test void listsRecentFeedbackWithOneDatabaseQuery() {
        var actor = UUID.randomUUID();
        var version = UUID.randomUUID();
        var feedback = new SolutionFeedback(UUID.randomUUID(), UUID.randomUUID(), version,
                null, actor, FeedbackReason.ACCEPTED, "feedback-1",
                Instant.parse("2026-10-29T00:00:00Z"));
        Map<String, Object> columns = new HashMap<>();
        columns.put("id", feedback.id());
        columns.put("solution_id", feedback.solutionId());
        columns.put("finalized_version_id", feedback.finalizedVersionId());
        columns.put("section_version_id", null);
        columns.put("actor_id", actor);
        columns.put("reason", feedback.reason().name());
        columns.put("client_key", feedback.clientKey());
        columns.put("created_at", OffsetDateTime.ofInstant(feedback.createdAt(), ZoneOffset.UTC));
        var row = proxy(ResultSet.class, (ignored, method, args) -> columns.get(args[0]));
        var queries = new AtomicInteger();
        var mapper = new AtomicReference<RowMapper<SolutionFeedback>>();
        var mapped = proxy(JdbcClient.MappedQuerySpec.class, (ignored, method, args) -> {
            var value = mapper.get().mapRow(row, 0);
            return method.getName().equals("optional") ? Optional.of(value) : List.of(value);
        });
        var statement = proxy(JdbcClient.StatementSpec.class, (self, method, args) -> {
            if (method.getName().equals("param")) return self;
            @SuppressWarnings("unchecked")
            RowMapper<SolutionFeedback> selected = (RowMapper<SolutionFeedback>) args[0];
            mapper.set(selected);
            return mapped;
        });
        var jdbc = proxy(JdbcClient.class, (ignored, method, args) -> {
            queries.incrementAndGet();
            return statement;
        });

        assertThat(new JdbcFeedbackStore(jdbc).listByActorAndVersion(actor, version))
                .containsExactly(feedback);
        assertThat(queries).hasValue(1);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }
}
