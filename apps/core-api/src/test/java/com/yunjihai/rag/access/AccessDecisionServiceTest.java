package com.yunjihai.rag.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessDecisionServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID SPACE_ID = UUID.fromString("00000000-0000-7000-8000-000000000100");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000200");
    private static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");

    @Test
    void deniesByDefaultWhenRepositoryFindsNoAuthorizedDocument() {
        AccessDecisionService service = serviceReturning(Optional.empty());

        assertThat(service.canRead(USER_ID, SPACE_ID, DOCUMENT_ID, NOW)).isFalse();
        assertThatThrownBy(() -> service.requireRead(USER_ID, SPACE_ID, DOCUMENT_ID, NOW))
                .isInstanceOf(AccessDeniedAsNotFoundException.class)
                .hasMessage("Knowledge resource not found");
    }

    @Test
    void allowsPublishedDocumentWhenSpaceAndRestrictedDocumentBothGrantAccess() {
        AccessSnapshot snapshot = new AccessSnapshot(
                true, true, true, PublicationState.PUBLISHED, NOW.plusSeconds(3600));

        assertThat(serviceReturning(Optional.of(snapshot))
                .canRead(USER_ID, SPACE_ID, DOCUMENT_ID, NOW)).isTrue();
    }

    @Test
    void deniesWhenDocumentRestrictionDoesNotIntersectSpaceGrant() {
        AccessSnapshot snapshot = new AccessSnapshot(
                true, true, false, PublicationState.PUBLISHED, NOW.plusSeconds(3600));

        assertThat(serviceReturning(Optional.of(snapshot))
                .canRead(USER_ID, SPACE_ID, DOCUMENT_ID, NOW)).isFalse();
    }

    @Test
    void inheritsSpaceGrantWhenDocumentHasNoNarrowerAcl() {
        AccessSnapshot snapshot = new AccessSnapshot(
                true, false, false, PublicationState.PUBLISHED, null);

        assertThat(serviceReturning(Optional.of(snapshot))
                .canRead(USER_ID, SPACE_ID, DOCUMENT_ID, NOW)).isTrue();
    }

    @Test
    void deniesArchivedOrExpiredDocument() {
        AccessSnapshot archived = new AccessSnapshot(
                true, false, false, PublicationState.ARCHIVED, null);
        AccessSnapshot expired = new AccessSnapshot(
                true, false, false, PublicationState.PUBLISHED, NOW);

        assertThat(serviceReturning(Optional.of(archived))
                .canRead(USER_ID, SPACE_ID, DOCUMENT_ID, NOW)).isFalse();
        assertThat(serviceReturning(Optional.of(expired))
                .canRead(USER_ID, SPACE_ID, DOCUMENT_ID, NOW)).isFalse();
    }

    private AccessDecisionService serviceReturning(Optional<AccessSnapshot> snapshot) {
        PrincipalMembershipRepository memberships = userId ->
                new PrincipalMembershipSnapshot(Set.of(), Map.of(), Set.of(), Set.of());
        AccessDecisionRepository decisions = (spaceId, documentId, principals, at) -> snapshot;
        return new AccessDecisionService(new EffectivePrincipalSet(memberships), decisions);
    }
}
