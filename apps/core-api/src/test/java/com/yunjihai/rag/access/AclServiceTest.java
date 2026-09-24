package com.yunjihai.rag.access;

import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditEvent;
import com.yunjihai.rag.audit.AuditService;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AclServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final PrincipalRef USER = new PrincipalRef(
            PrincipalType.USER, UUID.fromString("00000000-0000-0000-0000-000000000030"));

    @Test
    void documentGrantCannotExceedKnowledgeSpaceScope() {
        var fixture = new Fixture(KnowledgeWriteAuthorization.allowAll());

        assertThatThrownBy(() -> fixture.service.grantDocument(
                ACTOR_ID, SPACE_ID, DOCUMENT_ID, USER, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空间");

        assertThat(fixture.repository.documentGrants).isEmpty();
        assertThat(fixture.events).isEmpty();
    }

    @Test
    void grantsAndRevokesSpaceAndDocumentAclWithAppendOnlyAudit() {
        var fixture = new Fixture(KnowledgeWriteAuthorization.allowAll());

        fixture.service.grantSpace(ACTOR_ID, SPACE_ID, USER, null, NOW);
        fixture.service.grantDocument(ACTOR_ID, SPACE_ID, DOCUMENT_ID, USER, null, NOW);
        fixture.service.revokeDocument(ACTOR_ID, SPACE_ID, DOCUMENT_ID, USER, NOW);
        fixture.service.revokeSpace(ACTOR_ID, SPACE_ID, USER, NOW);

        assertThat(fixture.repository.spaceGrants).isEmpty();
        assertThat(fixture.repository.documentGrants).isEmpty();
        assertThat(fixture.repository.restrictedDocuments).containsExactly(DOCUMENT_ID);
        assertThat(fixture.events)
                .extracting(AuditEvent::action)
                .containsExactly(
                        AuditAction.ACL_CHANGE,
                        AuditAction.ACL_CHANGE,
                        AuditAction.ACL_CHANGE,
                        AuditAction.ACL_CHANGE);
        assertThat(fixture.events)
                .allSatisfy(event -> assertThat(event.metadata())
                        .containsKeys("spaceId", "permissionScope", "targetPrincipalType", "targetPrincipalId"));
    }

    @Test
    void authorizationFailurePreventsAclMutationAndAudit() {
        var fixture = new Fixture(KnowledgeWriteAuthorization.denyAll());

        assertThatThrownBy(() -> fixture.service.grantSpace(
                ACTOR_ID, SPACE_ID, USER, null, NOW))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);

        assertThat(fixture.repository.spaceGrants).isEmpty();
        assertThat(fixture.events).isEmpty();
    }

    private static final class Fixture {
        private final MemoryAclRepository repository = new MemoryAclRepository();
        private final List<AuditEvent> events = new ArrayList<>();
        private final AclService service;

        private Fixture(KnowledgeWriteAuthorization authorization) {
            service = new AclService(
                    repository,
                    authorization,
                    new AuditService(events::add, Clock.fixed(NOW, ZoneOffset.UTC)));
        }
    }

    private static final class MemoryAclRepository implements AclCommandRepository {
        private final Set<Key> spaceGrants = new HashSet<>();
        private final Set<DocumentKey> documentGrants = new HashSet<>();
        private final Set<UUID> restrictedDocuments = new HashSet<>();

        @Override
        public boolean isWithinSpaceScope(UUID spaceId, PrincipalRef principal, Instant at) {
            return spaceGrants.contains(new Key(spaceId, principal));
        }

        @Override
        public void grantSpace(UUID spaceId, PrincipalRef principal, Instant validUntil, Instant at) {
            spaceGrants.add(new Key(spaceId, principal));
        }

        @Override
        public void grantDocument(
                UUID spaceId,
                UUID documentId,
                PrincipalRef principal,
                Instant validUntil,
                Instant at) {
            restrictedDocuments.add(documentId);
            documentGrants.add(new DocumentKey(spaceId, documentId, principal));
        }

        @Override
        public void revokeSpace(UUID spaceId, PrincipalRef principal, Instant at) {
            spaceGrants.remove(new Key(spaceId, principal));
        }

        @Override
        public void revokeDocument(
                UUID spaceId, UUID documentId, PrincipalRef principal, Instant at) {
            documentGrants.remove(new DocumentKey(spaceId, documentId, principal));
        }

        private record Key(UUID spaceId, PrincipalRef principal) {
        }

        private record DocumentKey(UUID spaceId, UUID documentId, PrincipalRef principal) {
        }
    }
}
