package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.ingestion.BlobCleanupEligibilityRepository;
import com.yunjihai.rag.ingestion.OrphanBlobCandidate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;

public final class JdbcBlobCleanupEligibilityRepository implements BlobCleanupEligibilityRepository {
    private final JdbcClient jdbc;

    public JdbcBlobCleanupEligibilityRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public boolean isEligible(OrphanBlobCandidate candidate) {
        Objects.requireNonNull(candidate);
        try {
            return jdbc.sql("""
                    SELECT NOT EXISTS (
                        SELECT 1 FROM document_version WHERE source_blob_id = :objectKey
                    ) AND NOT EXISTS (
                        SELECT 1 FROM active_blob_reservation WHERE sha256 = :sha256
                    )
                    """)
                    .param("objectKey", candidate.objectKey())
                    .param("sha256", candidate.sha256())
                    .query(Boolean.class)
                    .single();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
