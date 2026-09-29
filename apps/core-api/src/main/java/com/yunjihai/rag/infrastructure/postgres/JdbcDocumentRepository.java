package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.knowledge.ConcurrentDocumentUpdateException;
import com.yunjihai.rag.knowledge.DocumentAggregate;
import com.yunjihai.rag.knowledge.DocumentRepository;
import com.yunjihai.rag.knowledge.DocumentStatus;
import com.yunjihai.rag.knowledge.DocumentVersion;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.knowledge.ParseStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Explicit SQL adapter; transaction boundaries include the parent row, versions, and active reservations. */
public class JdbcDocumentRepository implements DocumentRepository {
    private static final Pattern OBJECT_KEY = Pattern.compile("^sha256/([0-9a-f]{2})/([0-9a-f]{64})$");
    private final JdbcClient jdbc;

    public JdbcDocumentRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    @Transactional
    public void insert(DocumentAggregate document) {
        validateVersionSources(document);
        try {
            jdbc.sql("""
                    INSERT INTO document (id, space_id, title, archived, revision, created_at, updated_at)
                    VALUES (:id, :spaceId, :title, :archived, :revision, :createdAt, :updatedAt)
                    """)
                    .param("id", document.id())
                    .param("spaceId", document.spaceId())
                    .param("title", document.title())
                    .param("archived", document.archived())
                    .param("revision", document.revision())
                    .param("createdAt", utc(document.versions().getFirst().createdAt()))
                    .param("updatedAt", utc(document.versions().getLast().createdAt()))
                    .update();
            for (var version : document.versions()) insertVersion(document.id(), version);
            setPublishedPointer(document);
            writeReservations(document);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public Optional<DocumentAggregate> findById(UUID documentId) {
        try {
            return jdbc.sql("""
                    SELECT id, space_id, title, current_published_version_id, archived, revision
                    FROM document WHERE id = :id
                    """)
                    .param("id", documentId)
                    .query((rs, row) -> new DocumentAggregate(
                            rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                            rs.getString("title"), readVersions(documentId),
                            rs.getObject("current_published_version_id", UUID.class),
                            rs.getBoolean("archived"), rs.getLong("revision")))
                    .optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public Optional<DocumentVersionRef> findActiveVersionBySpaceAndSourceBlob(UUID spaceId, String sourceBlobId) {
        try {
            return jdbc.sql("""
                    SELECT reservation.document_id, reservation.version_id
                    FROM active_blob_reservation AS reservation
                    JOIN document_version AS version ON version.id = reservation.version_id
                    JOIN document AS document ON document.id = reservation.document_id
                    WHERE reservation.space_id = :spaceId AND version.source_blob_id = :sourceBlobId
                      AND document.archived = false
                    """)
                    .param("spaceId", spaceId)
                    .param("sourceBlobId", sourceBlobId)
                    .query((rs, row) -> new DocumentVersionRef(
                            rs.getObject("document_id", UUID.class), rs.getObject("version_id", UUID.class)))
                    .optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    @Transactional
    public void replace(DocumentAggregate document, long expectedRevision) {
        validateVersionSources(document);
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE
                || document.revision() != expectedRevision + 1) {
            throw new ConcurrentDocumentUpdateException();
        }
        try {
            int updated = jdbc.sql("""
                    UPDATE document SET title = :title, archived = :archived, revision = :revision,
                        updated_at = now()
                    WHERE id = :id AND space_id = :spaceId AND revision = :expectedRevision
                    """)
                    .param("title", document.title())
                    .param("archived", document.archived())
                    .param("revision", document.revision())
                    .param("id", document.id())
                    .param("spaceId", document.spaceId())
                    .param("expectedRevision", expectedRevision)
                    .update();
            if (updated == 0) throw new ConcurrentDocumentUpdateException();
            var persisted = readVersions(document.id());
            validateHistory(document, persisted);
            for (int index = 0; index < persisted.size(); index++) {
                updateVersion(document.id(), document.versions().get(index));
            }
            for (int index = persisted.size(); index < document.versions().size(); index++) {
                insertVersion(document.id(), document.versions().get(index));
            }
            setPublishedPointer(document);
            writeReservations(document);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private List<DocumentVersion> readVersions(UUID documentId) {
        return jdbc.sql("""
                SELECT id, version_number, source_blob_id, status, parse_status, created_by, created_at,
                       status_changed_by, status_changed_at
                FROM document_version WHERE document_id = :documentId ORDER BY version_number
                """)
                .param("documentId", documentId)
                .query((rs, row) -> mapVersion(rs))
                .list();
    }

    private static DocumentVersion mapVersion(ResultSet rs) throws SQLException {
        OffsetDateTime changed = rs.getObject("status_changed_at", OffsetDateTime.class);
        return new DocumentVersion(
                rs.getObject("id", UUID.class), rs.getInt("version_number"), rs.getString("source_blob_id"),
                DocumentStatus.valueOf(rs.getString("status")), ParseStatus.valueOf(rs.getString("parse_status")),
                rs.getObject("created_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("status_changed_by", UUID.class), changed == null ? null : changed.toInstant());
    }

    private void insertVersion(UUID documentId, DocumentVersion version) {
        jdbc.sql("""
                INSERT INTO document_version (id, document_id, version_number, source_blob_id, status,
                                              parse_status, created_by, created_at, status_changed_by, status_changed_at)
                VALUES (:id, :documentId, :versionNumber, :sourceBlobId, :status, :parseStatus,
                        :createdBy, :createdAt, :statusChangedBy, :statusChangedAt)
                """)
                .param("id", version.id())
                .param("documentId", documentId)
                .param("versionNumber", version.versionNumber())
                .param("sourceBlobId", version.sourceBlobId())
                .param("status", version.status().name())
                .param("parseStatus", version.parseStatus().name())
                .param("createdBy", version.createdBy())
                .param("createdAt", utc(version.createdAt()))
                .param("statusChangedBy", version.statusChangedBy())
                .param("statusChangedAt", version.statusChangedAt() == null ? null : utc(version.statusChangedAt()))
                .update();
    }

    private void validateHistory(DocumentAggregate document, List<DocumentVersion> persisted) {
        if (document.versions().size() < persisted.size()) throw new ConcurrentDocumentUpdateException();
        var ids = new HashSet<UUID>();
        for (int index = 0; index < persisted.size(); index++) {
            var old = persisted.get(index);
            var proposed = document.versions().get(index);
            if (!ids.add(proposed.id()) || !old.id().equals(proposed.id())
                    || old.versionNumber() != proposed.versionNumber()
                    || !old.sourceBlobId().equals(proposed.sourceBlobId())
                    || !old.createdBy().equals(proposed.createdBy())
                    || !old.createdAt().equals(proposed.createdAt())) {
                throw new ConcurrentDocumentUpdateException();
            }
        }
        long nextNumber = persisted.isEmpty() ? 1L : (long) persisted.getLast().versionNumber() + 1L;
        for (int index = persisted.size(); index < document.versions().size(); index++) {
            var proposed = document.versions().get(index);
            if (nextNumber > Integer.MAX_VALUE || proposed.versionNumber() != nextNumber++
                    || !ids.add(proposed.id())
                    || jdbc.sql("SELECT id FROM document_version WHERE id = :id")
                            .param("id", proposed.id()).query(UUID.class).optional().isPresent()) {
                throw new ConcurrentDocumentUpdateException();
            }
        }
    }

    private void updateVersion(UUID documentId, DocumentVersion version) {
        int updated = jdbc.sql("""
                UPDATE document_version SET status = :status, parse_status = :parseStatus,
                    status_changed_by = :changedBy, status_changed_at = :changedAt
                WHERE id = :id AND document_id = :documentId AND version_number = :versionNumber
                  AND source_blob_id = :sourceBlobId AND created_by = :createdBy AND created_at = :createdAt
                """)
                .param("status", version.status().name())
                .param("parseStatus", version.parseStatus().name())
                .param("changedBy", version.statusChangedBy())
                .param("changedAt", version.statusChangedAt() == null ? null : utc(version.statusChangedAt()))
                .param("id", version.id())
                .param("documentId", documentId)
                .param("versionNumber", version.versionNumber())
                .param("sourceBlobId", version.sourceBlobId())
                .param("createdBy", version.createdBy())
                .param("createdAt", utc(version.createdAt()))
                .update();
        if (updated == 0) throw new ConcurrentDocumentUpdateException();
    }

    private void setPublishedPointer(DocumentAggregate document) {
        jdbc.sql("UPDATE document SET current_published_version_id = :versionId WHERE id = :id")
                .param("versionId", document.publishedVersionId())
                .param("id", document.id())
                .update();
    }

    private void writeReservations(DocumentAggregate document) {
        jdbc.sql("DELETE FROM active_blob_reservation WHERE document_id = :documentId")
                .param("documentId", document.id()).update();
        if (document.archived()) return;
        for (var version : document.versions()) {
            jdbc.sql("""
                    INSERT INTO active_blob_reservation (space_id, sha256, document_id, version_id, created_at)
                    VALUES (:spaceId, :sha256, :documentId, :versionId, :createdAt)
                    """)
                    .param("spaceId", document.spaceId())
                    .param("sha256", digest(version.sourceBlobId()))
                    .param("documentId", document.id())
                    .param("versionId", version.id())
                    .param("createdAt", utc(version.createdAt()))
                    .update();
        }
    }

    private static void validateVersionSources(DocumentAggregate document) {
        Objects.requireNonNull(document);
        for (var version : document.versions()) digest(version.sourceBlobId());
    }

    private static String digest(String sourceBlobId) {
        var match = OBJECT_KEY.matcher(sourceBlobId);
        if (!match.matches() || !match.group(2).startsWith(match.group(1))) {
            throw new IllegalArgumentException("源 Blob 标识必须为规范 SHA-256 对象键");
        }
        return match.group(2);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
