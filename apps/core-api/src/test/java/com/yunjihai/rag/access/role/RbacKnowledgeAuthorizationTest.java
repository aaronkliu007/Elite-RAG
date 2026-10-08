package com.yunjihai.rag.access.role;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RbacKnowledgeAuthorizationTest {
    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID SPACE_ID = UUID.fromString("00000000-0000-7000-8000-000000000010");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000020");

    @Test
    void systemAdministratorCanPerformEveryAction() {
        var store = new InMemoryRoleStore();
        store.systemAdmin = true;
        var authorization = new RbacKnowledgeAuthorization(store);

        for (var action : KnowledgeWriteAuthorization.Action.values()) {
            assertThatCode(() -> authorization.require(
                    USER_ID,
                    action == KnowledgeWriteAuthorization.Action.CREATE_SPACE ? null : SPACE_ID,
                    documentId(action),
                    action)).doesNotThrowAnyException();
        }
    }

    @Test
    void spaceAdministratorCanPerformEverySpaceActionButCannotCreateSpaces() {
        assertAllowed(SpaceRole.SPACE_ADMIN, EnumSet.complementOf(
                EnumSet.of(KnowledgeWriteAuthorization.Action.CREATE_SPACE)));
        assertDenied(SpaceRole.SPACE_ADMIN, Set.of(KnowledgeWriteAuthorization.Action.CREATE_SPACE));
    }

    @Test
    void editorCanCreateEditAndSubmitButCannotReviewPublishArchiveOrManageAccess() {
        assertAllowed(SpaceRole.EDITOR, EnumSet.of(
                KnowledgeWriteAuthorization.Action.CREATE_DOCUMENT,
                KnowledgeWriteAuthorization.Action.EDIT_DOCUMENT,
                KnowledgeWriteAuthorization.Action.SUBMIT_FOR_REVIEW));
        assertDenied(SpaceRole.EDITOR, EnumSet.of(
                KnowledgeWriteAuthorization.Action.CREATE_SPACE,
                KnowledgeWriteAuthorization.Action.REVIEW_DOCUMENT,
                KnowledgeWriteAuthorization.Action.PUBLISH_DOCUMENT,
                KnowledgeWriteAuthorization.Action.ARCHIVE_DOCUMENT,
                KnowledgeWriteAuthorization.Action.MANAGE_ACL));
    }

    @Test
    void reviewerPublisherCanOnlyReviewAndPublish() {
        assertAllowed(SpaceRole.REVIEWER_PUBLISHER, EnumSet.of(
                KnowledgeWriteAuthorization.Action.REVIEW_DOCUMENT,
                KnowledgeWriteAuthorization.Action.PUBLISH_DOCUMENT));
        assertDenied(SpaceRole.REVIEWER_PUBLISHER, EnumSet.complementOf(EnumSet.of(
                KnowledgeWriteAuthorization.Action.REVIEW_DOCUMENT,
                KnowledgeWriteAuthorization.Action.PUBLISH_DOCUMENT)));
    }

    @Test
    void readerAndUnassignedUsersHaveNoWriteActions() {
        assertDenied(SpaceRole.READER, EnumSet.allOf(KnowledgeWriteAuthorization.Action.class));
        assertDenied(null, EnumSet.allOf(KnowledgeWriteAuthorization.Action.class));
    }

    private static void assertAllowed(SpaceRole role, Set<KnowledgeWriteAuthorization.Action> actions) {
        var authorization = authorization(role);
        for (var action : actions) {
            assertThatCode(() -> authorization.require(
                    USER_ID, SPACE_ID, documentId(action), action)).doesNotThrowAnyException();
        }
    }

    private static void assertDenied(SpaceRole role, Set<KnowledgeWriteAuthorization.Action> actions) {
        var authorization = authorization(role);
        for (var action : actions) {
            assertThatThrownBy(() -> authorization.require(
                    USER_ID,
                    action == KnowledgeWriteAuthorization.Action.CREATE_SPACE ? null : SPACE_ID,
                    documentId(action),
                    action)).isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);
        }
    }

    private static UUID documentId(KnowledgeWriteAuthorization.Action action) {
        return switch (action) {
            case EDIT_DOCUMENT, SUBMIT_FOR_REVIEW, REVIEW_DOCUMENT,
                    PUBLISH_DOCUMENT, ARCHIVE_DOCUMENT -> DOCUMENT_ID;
            default -> null;
        };
    }

    private static RbacKnowledgeAuthorization authorization(SpaceRole role) {
        var store = new InMemoryRoleStore();
        if (role != null) {
            store.roles.put(SPACE_ID, EnumSet.of(role));
        }
        return new RbacKnowledgeAuthorization(store);
    }

    private static final class InMemoryRoleStore implements RoleAssignmentStore {
        private boolean systemAdmin;
        private final Map<UUID, Set<SpaceRole>> roles = new HashMap<>();

        @Override public boolean hasSystemRole(UUID userId, SystemRole role) {
            return systemAdmin && role == SystemRole.SYSTEM_ADMIN;
        }
        @Override public boolean hasAnySystemRole(SystemRole role) {
            return systemAdmin && role == SystemRole.SYSTEM_ADMIN;
        }
        @Override public Set<SpaceRole> activeSpaceRoles(UUID userId, UUID spaceId) {
            return Set.copyOf(roles.getOrDefault(spaceId, Set.of()));
        }
        @Override public void grantSystemRole(UUID userId, SystemRole role, UUID actorId, Instant at) {
            systemAdmin = true;
        }
        @Override public void grantSpaceRole(UUID spaceId, UUID userId, SpaceRole role, UUID actorId, Instant at) {
            throw new UnsupportedOperationException();
        }
        @Override public void revokeSpaceRole(UUID spaceId, UUID userId, SpaceRole role, UUID actorId, Instant at) {
            throw new UnsupportedOperationException();
        }
    }
}
