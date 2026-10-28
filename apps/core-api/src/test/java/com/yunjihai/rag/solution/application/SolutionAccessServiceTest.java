package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.role.*;
import com.yunjihai.rag.solution.SolutionAction;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SolutionAccessServiceTest {
    private final UUID actor = UUID.randomUUID();
    private final UUID space = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-10-28T10:00:00Z");

    @Test void systemAdminWithoutSpaceReadIsHidden() {
        var access = new SolutionAccessService((user, target, checked) -> {
            throw new AccessDeniedAsNotFoundException();
        }, new Roles(true, Set.of()));
        assertThatThrownBy(() -> access.requireAction(actor, space, UUID.randomUUID(),
                SolutionAction.EDIT_REQUIREMENTS, at)).isInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    @Test void mapsEditorAndReviewerToDistinctActions() {
        var editor = new SolutionAccessService((user, target, checked) -> {},
                new Roles(false, Set.of(SpaceRole.EDITOR)));
        assertThat(editor.allowedActions(actor, space, at)).containsExactlyInAnyOrder(
                SolutionAction.EDIT_REQUIREMENTS, SolutionAction.EDIT_OUTLINE,
                SolutionAction.ADOPT_SECTION);
        assertThatCode(() -> editor.requireAction(actor, space, UUID.randomUUID(),
                SolutionAction.ADOPT_SECTION, at)).doesNotThrowAnyException();
        assertThatThrownBy(() -> editor.requireAction(actor, space, UUID.randomUUID(),
                SolutionAction.CONFIRM_OUTLINE, at)).isInstanceOf(AccessDeniedAsNotFoundException.class);
        var reviewer = new SolutionAccessService((user, target, checked) -> {},
                new Roles(false, Set.of(SpaceRole.REVIEWER_PUBLISHER)));
        assertThat(reviewer.allowedActions(actor, space, at)).containsExactlyInAnyOrder(
                SolutionAction.CONFIRM_REQUIREMENTS, SolutionAction.CONFIRM_OUTLINE);
    }

    @Test void defaultReviewPolicyRejectsSelfConfirmation() {
        var policy = new SolutionReviewPolicy(false);
        assertThat(policy.canConfirm(actor, actor)).isFalse();
        assertThat(policy.canConfirm(actor, UUID.randomUUID())).isTrue();
    }

    private record Roles(boolean admin, Set<SpaceRole> active) implements RoleAssignmentStore {
        @Override public boolean hasSystemRole(UUID userId, SystemRole role) { return admin; }
        @Override public boolean hasAnySystemRole(SystemRole role) { return admin; }
        @Override public Set<SpaceRole> activeSpaceRoles(UUID userId, UUID spaceId) { return active; }
        @Override public void grantSystemRole(UUID userId, SystemRole role, UUID actorId, Instant at) { throw new UnsupportedOperationException(); }
        @Override public void grantSpaceRole(UUID spaceId, UUID userId, SpaceRole role, UUID actorId, Instant at) { throw new UnsupportedOperationException(); }
        @Override public void revokeSpaceRole(UUID spaceId, UUID userId, SpaceRole role, UUID actorId, Instant at) { throw new UnsupportedOperationException(); }
    }
}
