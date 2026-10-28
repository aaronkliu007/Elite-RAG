package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.role.*;
import com.yunjihai.rag.knowledge.KnowledgeSpaceReadAuthorization;
import com.yunjihai.rag.solution.*;
import java.time.Instant;
import java.util.*;

public final class SolutionAccessService implements SolutionAuthorizationPort {
    private final KnowledgeSpaceReadAuthorization spaces;
    private final RoleAssignmentStore roles;

    public SolutionAccessService(KnowledgeSpaceReadAuthorization spaces, RoleAssignmentStore roles) {
        this.spaces = Objects.requireNonNull(spaces);
        this.roles = Objects.requireNonNull(roles);
    }

    public void requireView(UUID actorId, UUID spaceId, Instant at) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(at);
        try {
            spaces.requireRead(actorId, spaceId, at);
        } catch (AccessDeniedAsNotFoundException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new SolutionAuthorityUnavailableException();
        }
    }

    public void requireAction(UUID actorId, UUID spaceId, UUID solutionId, SolutionAction action, Instant at) {
        Objects.requireNonNull(solutionId);
        Objects.requireNonNull(action);
        if (!allowedActions(actorId, spaceId, at).contains(action)) {
            throw new AccessDeniedAsNotFoundException();
        }
    }

    @Override public void require(UUID actorId, UUID spaceId, UUID solutionId, SolutionAction action, Instant at) {
        requireAction(actorId, spaceId, solutionId, action, at);
    }

    public Set<SolutionAction> allowedActions(UUID actorId, UUID spaceId, Instant at) {
        requireView(actorId, spaceId, at);
        final boolean administrator;
        final Set<SpaceRole> active;
        try {
            administrator = roles.hasSystemRole(actorId, SystemRole.SYSTEM_ADMIN);
            active = Objects.requireNonNull(roles.activeSpaceRoles(actorId, spaceId));
        } catch (RuntimeException unavailable) {
            throw new SolutionAuthorityUnavailableException();
        }
        EnumSet<SolutionAction> allowed = EnumSet.noneOf(SolutionAction.class);
        if (administrator || active.contains(SpaceRole.SPACE_ADMIN) || active.contains(SpaceRole.EDITOR)) {
            allowed.add(SolutionAction.EDIT_REQUIREMENTS);
            allowed.add(SolutionAction.EDIT_OUTLINE);
        }
        if (administrator || active.contains(SpaceRole.SPACE_ADMIN) || active.contains(SpaceRole.REVIEWER_PUBLISHER)) {
            allowed.add(SolutionAction.CONFIRM_REQUIREMENTS);
            allowed.add(SolutionAction.CONFIRM_OUTLINE);
        }
        return Set.copyOf(allowed);
    }
}
