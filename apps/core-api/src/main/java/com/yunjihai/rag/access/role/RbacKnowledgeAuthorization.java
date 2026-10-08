package com.yunjihai.rag.access.role;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class RbacKnowledgeAuthorization implements KnowledgeWriteAuthorization {
    private static final Set<Action> EDITOR_ACTIONS = Set.of(
            Action.CREATE_DOCUMENT,
            Action.EDIT_DOCUMENT,
            Action.SUBMIT_FOR_REVIEW);
    private static final Set<Action> REVIEWER_ACTIONS = Set.of(
            Action.REVIEW_DOCUMENT,
            Action.PUBLISH_DOCUMENT);

    private final RoleAssignmentStore roles;

    public RbacKnowledgeAuthorization(RoleAssignmentStore roles) {
        this.roles = Objects.requireNonNull(roles);
    }

    @Override
    public void require(UUID actorId, UUID spaceId, UUID documentId, Action action) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(action);
        if (roles.hasSystemRole(actorId, SystemRole.SYSTEM_ADMIN)) {
            return;
        }
        if (action == Action.CREATE_SPACE || spaceId == null) {
            throw new AccessDeniedAsNotFoundException();
        }
        Set<SpaceRole> assigned = roles.activeSpaceRoles(actorId, spaceId);
        if (assigned.contains(SpaceRole.SPACE_ADMIN)
                || (assigned.contains(SpaceRole.EDITOR) && EDITOR_ACTIONS.contains(action))
                || (assigned.contains(SpaceRole.REVIEWER_PUBLISHER) && REVIEWER_ACTIONS.contains(action))) {
            return;
        }
        throw new AccessDeniedAsNotFoundException();
    }
}
