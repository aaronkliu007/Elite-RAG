package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.infrastructure.minio.MinioBlobReader;
import com.yunjihai.rag.ingestion.UploadPolicy;
import com.yunjihai.rag.knowledge.DocumentContent;
import com.yunjihai.rag.knowledge.DocumentContentGateway;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL canonical 内容与 MinIO 原文件的只读组合适配器。 */
public final class JdbcDocumentContentGateway implements DocumentContentGateway {

    private static final long MAX_SOURCE_BYTES = UploadPolicy.defaults().maxBytes();
    private static final String PREVIEW_MEDIA_TYPE = "text/plain;charset=UTF-8";

    @FunctionalInterface
    interface SourceBlobReader {
        byte[] read(String objectKey, long maxBytes);
    }

    private final JdbcClient jdbc;
    private final SourceBlobReader blobs;

    public JdbcDocumentContentGateway(JdbcClient jdbc, MinioBlobReader blobs) {
        this(jdbc, blobs::read);
    }

    JdbcDocumentContentGateway(JdbcClient jdbc, SourceBlobReader blobs) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.blobs = Objects.requireNonNull(blobs);
    }

    @Override
    public DocumentContent readParsed(UUID documentId, UUID versionId) {
        requireIds(documentId, versionId);
        try {
            var sourceFilename = jdbc.sql("""
                            SELECT canonical.source_filename
                              FROM document_version version
                              JOIN canonical_document canonical ON canonical.version_id = version.id
                             WHERE version.document_id = :documentId
                               AND version.id = :versionId
                            """)
                    .param("documentId", documentId)
                    .param("versionId", versionId)
                    .query(String.class)
                    .optional()
                    .orElseThrow(JdbcDocumentContentGateway::contentMissing);
            var texts = jdbc.sql("""
                            SELECT node.body_text
                              FROM canonical_node node
                             WHERE node.version_id = :versionId
                             ORDER BY node.stable_order
                            """)
                    .param("versionId", versionId)
                    .query(String.class)
                    .list();
            var preview = String.join("\n\n", texts);
            return new DocumentContent(
                    PREVIEW_MEDIA_TYPE,
                    sourceFilename + ".txt",
                    preview.getBytes(StandardCharsets.UTF_8));
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public DocumentContent readSource(UUID documentId, UUID versionId) {
        requireIds(documentId, versionId);
        try {
            var source = jdbc.sql("""
                            SELECT version.source_blob_id, job.source_filename,
                                   job.media_type, job.byte_size
                              FROM document_version version
                              JOIN LATERAL (
                                  SELECT source_filename, media_type, byte_size
                                    FROM ingestion_job
                                   WHERE document_id = version.document_id
                                     AND version_id = version.id
                                   ORDER BY created_at, id
                                   LIMIT 1
                              ) job ON true
                             WHERE version.document_id = :documentId
                               AND version.id = :versionId
                            """)
                    .param("documentId", documentId)
                    .param("versionId", versionId)
                    .query(JdbcDocumentContentGateway::mapSource)
                    .optional()
                    .orElseThrow(JdbcDocumentContentGateway::contentMissing);
            if (source.byteSize() < 1 || source.byteSize() > MAX_SOURCE_BYTES) {
                throw new IllegalStateException("DOCUMENT_SOURCE_SIZE_INVALID", null);
            }
            var bytes = blobs.read(source.objectKey(), MAX_SOURCE_BYTES);
            if (bytes.length != source.byteSize()) {
                throw new IllegalStateException("DOCUMENT_SOURCE_LENGTH_MISMATCH", null);
            }
            return new DocumentContent(source.mediaType(), source.filename(), bytes);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private static SourceMetadata mapSource(ResultSet row, int rowNumber) throws SQLException {
        return new SourceMetadata(
                row.getString("source_blob_id"),
                row.getString("source_filename"),
                row.getString("media_type"),
                row.getLong("byte_size"));
    }

    private static void requireIds(UUID documentId, UUID versionId) {
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(versionId);
    }

    private static IllegalStateException contentMissing() {
        return new IllegalStateException("DOCUMENT_CONTENT_NOT_FOUND", null);
    }

    private record SourceMetadata(
            String objectKey,
            String filename,
            String mediaType,
            long byteSize) { }
}
