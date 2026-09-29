package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditEvent;
import com.yunjihai.rag.audit.AuditOutcome;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DocumentTransactionIntegrationTest extends DocumentPostgresFixture {
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
