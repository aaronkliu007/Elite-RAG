package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.solution.application.SolutionDocumentSourcePort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class JdbcSolutionDocumentSourcePort implements SolutionDocumentSourcePort {
    private final JdbcClient jdbc;
    private final AccessDecisionService access;

    public JdbcSolutionDocumentSourcePort(JdbcClient jdbc, AccessDecisionService access) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.access = Objects.requireNonNull(access);
    }

    @Override public boolean isCurrentPublishedAndReadable(
            UUID actorId, UUID spaceId, UUID documentId, UUID versionId, Instant at) {
        Objects.requireNonNull(actorId); Objects.requireNonNull(spaceId);
        Objects.requireNonNull(documentId); Objects.requireNonNull(versionId); Objects.requireNonNull(at);
        try {
            boolean current = jdbc.sql("""
                    SELECT EXISTS (
                        SELECT 1 FROM document d
                        JOIN document_version v ON v.id = d.current_published_version_id
                            AND v.document_id = d.id AND v.status = 'PUBLISHED'
                        WHERE d.id = :document AND d.space_id = :space AND v.id = :version AND NOT d.archived
                    )
                    """).param("document", documentId).param("space", spaceId)
                    .param("version", versionId).query(Boolean.class).single();
            return current && access.canRead(actorId, spaceId, documentId, at);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
