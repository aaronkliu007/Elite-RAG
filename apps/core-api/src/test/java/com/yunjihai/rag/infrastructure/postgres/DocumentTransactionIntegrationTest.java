package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditEvent;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.knowledge.ConcurrentDocumentUpdateException;
import com.yunjihai.rag.knowledge.DocumentLifecycleException;
import com.yunjihai.rag.knowledge.DocumentMetadata;
import com.yunjihai.rag.knowledge.DocumentStatus;
import com.yunjihai.rag.knowledge.ReviewDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DocumentTransactionIntegrationTest extends DocumentPostgresFixture {
    @Test
    void concurrentPublishCreatesExactlyOneCurrentVersionReviewAuditAndProjectionEvent() throws Exception {
        UUID space = createSpace();
        var initial = draft(space, 'a');
        documents.insert(initial);
        var versionId = initial.latestVersion().id();
        service.updateMetadata(initial.id(), new DocumentMetadata("制度", List.of("正式")),
                actor, AT.plusSeconds(1));
        ownerJdbc.sql("DELETE FROM audit_event").update();
        service.completeParsing(initial.id(), versionId, actor, AT.plusSeconds(2));
        service.submitForReview(initial.id(), versionId, actor, AT.plusSeconds(3));
        ownerJdbc.sql("DELETE FROM audit_event").update();
        ownerJdbc.sql("DELETE FROM retrieval_projection_event").update();

        UUID otherReviewer = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :externalId, 'Reviewer')")
                .param("id", otherReviewer).param("externalId", otherReviewer.toString()).update();
        var result = race(
                () -> service.publish(initial.id(), versionId, actor, AT.plusSeconds(4)),
                () -> service.publish(initial.id(), versionId, otherReviewer, AT.plusSeconds(4)));

        assertEquals(1, result.successes());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().getFirst() instanceof DocumentLifecycleException
                || result.failures().getFirst() instanceof ConcurrentDocumentUpdateException);
        var persisted = documents.findById(initial.id()).orElseThrow();
        assertEquals(versionId, persisted.currentPublishedVersionId().orElseThrow());
        assertEquals(DocumentStatus.PUBLISHED, persisted.version(versionId).status());
        assertEquals(1L, count("document_review"));
        assertEquals(ReviewDecision.PUBLISHED.name(), ownerJdbc.sql(
                "SELECT decision FROM document_review").query(String.class).single());
        assertEquals(1L, count("audit_event"));
        assertEquals(1L, count("retrieval_projection_event"));
    }

    @Test
    void failedRealAuditAppendRollsBackDocumentVersionAndReservation() {
        UUID space = createSpace();
        var initial = draft(space, 'a');
        documents.insert(initial);
        new JdbcAuditEventStore(jdbc).append(new AuditEvent(FIXED_AUDIT_ID, actor, AuditAction.EDIT,
                "DOCUMENT_VERSION", UUID.randomUUID(), AuditOutcome.SUCCEEDED, Map.of(), AT));

        var failure = assertThrows(PostgresPersistenceExceptionTranslator.Failure.class,
                () -> service.createNextDraft(initial.id(), blob('b'), actor, AT.plusSeconds(1)));

        assertEquals("UNIQUE_CONSTRAINT", failure.code());
        assertNull(failure.getCause());
        assertEquals(initial, documents.findById(initial.id()).orElseThrow());
        assertEquals(1L, count("document"));
        assertEquals(1L, count("document_version"));
        assertEquals(1L, count("active_blob_reservation"));
        assertEquals(1L, count("audit_event"));
    }
}
