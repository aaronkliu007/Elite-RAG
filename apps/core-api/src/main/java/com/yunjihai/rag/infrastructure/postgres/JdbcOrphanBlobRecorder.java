package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.ingestion.OrphanBlobCandidate;
import com.yunjihai.rag.ingestion.OrphanBlobRecorder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

public class JdbcOrphanBlobRecorder implements OrphanBlobRecorder {
    private final JdbcClient jdbc;

    public JdbcOrphanBlobRecorder(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    // 对象写入无法参与数据库回滚；即使调用事务已 aborted，补偿事实也必须独立提交。
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(OrphanBlobCandidate candidate) {
        Objects.requireNonNull(candidate);
        try {
            var recorded = jdbc.sql("""
                    INSERT INTO blob_cleanup_candidate (id, bucket, sha256, object_key)
                    VALUES (:id, :bucket, :sha256, :objectKey)
                    ON CONFLICT (bucket, object_key) DO UPDATE
                    SET status = 'PENDING',
                        registered_at = now(),
                        last_checked_at = NULL
                    WHERE blob_cleanup_candidate.sha256 = EXCLUDED.sha256
                    RETURNING sha256
                    """)
                    .param("id", UUID.randomUUID())
                    .param("bucket", candidate.bucket())
                    .param("sha256", candidate.sha256())
                    .param("objectKey", candidate.objectKey())
                    .query(String.class)
                    .optional();
            if (recorded.isEmpty()) {
                throw new IllegalStateException("BLOB_CLEANUP_CONFLICT", null);
            }
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
