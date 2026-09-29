package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.ingestion.OrphanBlobCandidate;
import com.yunjihai.rag.ingestion.OrphanBlobRecorder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.UUID;

public final class JdbcOrphanBlobRecorder implements OrphanBlobRecorder {
    private final JdbcClient jdbc;

    public JdbcOrphanBlobRecorder(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void record(OrphanBlobCandidate candidate) {
        Objects.requireNonNull(candidate);
        try {
            jdbc.sql("""
                    INSERT INTO blob_cleanup_candidate (id, bucket, sha256, object_key)
                    VALUES (:id, :bucket, :sha256, :objectKey)
                    ON CONFLICT (bucket, object_key) DO NOTHING
                    """)
                    .param("id", UUID.randomUUID())
                    .param("bucket", candidate.bucket())
                    .param("sha256", candidate.sha256())
                    .param("objectKey", candidate.objectKey())
                    .update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
