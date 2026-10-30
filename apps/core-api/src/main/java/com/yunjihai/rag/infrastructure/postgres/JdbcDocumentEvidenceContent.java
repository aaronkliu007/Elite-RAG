package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.application.DocumentEvidenceContentPort;
import com.yunjihai.rag.qa.sanitization.SensitiveContentSanitizer;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.*;

public final class JdbcDocumentEvidenceContent implements DocumentEvidenceContentPort {
    private final JdbcClient jdbc;
    private final SensitiveContentSanitizer sanitizer;
    public JdbcDocumentEvidenceContent(JdbcClient jdbc, SensitiveContentSanitizer sanitizer) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.sanitizer = Objects.requireNonNull(sanitizer);
    }
    @Override public Optional<String> readExcerpt(UUID spaceId, UUID documentId, UUID versionId, String nodeId) {
        Objects.requireNonNull(spaceId); Objects.requireNonNull(documentId); Objects.requireNonNull(versionId); Objects.requireNonNull(nodeId);
        try {
            return jdbc.sql("""
                    SELECT n.body_text FROM canonical_node n
                    JOIN document_version v ON v.id = n.version_id AND v.status = 'PUBLISHED'
                    JOIN document d ON d.id = v.document_id AND d.current_published_version_id = v.id
                    WHERE d.space_id = :space AND d.id = :document AND v.id = :version
                      AND NOT d.archived AND n.node_id = :node
                    """).param("space", spaceId).param("document", documentId).param("version", versionId)
                    .param("node", nodeId).query(String.class).optional().flatMap(body -> {
                        var sanitized = sanitizer.sanitizeText(body);
                        if (sanitized.blocked()) return Optional.empty();
                        String value = sanitized.value();
                        int length = value.codePointCount(0, value.length());
                        return Optional.of(length <= 1200 ? value : value.substring(0, value.offsetByCodePoints(0, 1200)));
                    });
        } catch (DataAccessException failure) { throw PostgresPersistenceExceptionTranslator.translate(failure); }
    }
}
