package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.role.*;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.solution.SolutionAction;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.port.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class SolutionGenerationAuthorizationServiceTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID SOLUTION = UUID.randomUUID();
    private static final UUID DOCUMENT = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");

    @Test void rechecksCurrentDocumentSourceEveryTime() {
        var readable = new AtomicBoolean(true);
        var requirement = requirement();
        var solution = Solution.create(SOLUTION, SPACE, ACTOR, requirement, NOW);
        SolutionRepository solutions = new SolutionRepository() {
            public Optional<Solution> findById(UUID id) {
                return id.equals(SOLUTION) ? Optional.of(solution) : Optional.empty();
            }
            public void insert(Solution ignored) { throw new UnsupportedOperationException(); }
            public void replace(Solution ignored, long revision) { throw new UnsupportedOperationException(); }
        };
        RequirementVersionRepository requirements = new RequirementVersionRepository() {
            public Optional<RequirementVersion> findById(UUID id) {
                return id.equals(requirement.id()) ? Optional.of(requirement) : Optional.empty();
            }
            public void insert(RequirementVersion ignored) { throw new UnsupportedOperationException(); }
        };
        var spaces = new com.yunjihai.rag.knowledge.KnowledgeSpaceReadAuthorization() {
            public void requireRead(UUID actor, UUID space, Instant at) {}
        };
        var roles = new RoleAssignmentStore() {
            public boolean hasSystemRole(UUID user, SystemRole role) { return false; }
            public boolean hasAnySystemRole(SystemRole role) { return false; }
            public Set<SpaceRole> activeSpaceRoles(UUID user, UUID space) { return Set.of(SpaceRole.EDITOR); }
            public void grantSystemRole(UUID user, SystemRole role, UUID actor, Instant at) { throw new UnsupportedOperationException(); }
            public void grantSpaceRole(UUID space, UUID user, SpaceRole role, UUID actor, Instant at) { throw new UnsupportedOperationException(); }
            public void revokeSpaceRole(UUID space, UUID user, SpaceRole role, UUID actor, Instant at) { throw new UnsupportedOperationException(); }
        };
        var access = new SolutionAccessService(spaces, roles);
        var sources = new SolutionSourceAccess(spaces,
                (actor, space, document, version, at) -> readable.get()
                        && document.equals(DOCUMENT) && version.equals(VERSION));
        var authorization = new SolutionGenerationAuthorizationService(access, sources, solutions, requirements);

        authorization.require(ACTOR, SPACE, SOLUTION, SolutionAction.ADOPT_SECTION, NOW);
        readable.set(false);
        assertThatThrownBy(() -> authorization.require(ACTOR, SPACE, SOLUTION,
                SolutionAction.ADOPT_SECTION, NOW.plusSeconds(1)))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(() -> authorization.require(ACTOR, UUID.randomUUID(), SOLUTION,
                SolutionAction.ADOPT_SECTION, NOW))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    private static RequirementVersion requirement() {
        return new RequirementVersion(UUID.randomUUID(), 1,
                List.of(new RequirementItem(UUID.randomUUID(), RequirementCategory.GOAL, "目标",
                        List.of(new RequirementSource(new DocumentVersionRef(DOCUMENT, VERSION),
                                new PageLocator(1, null))))), ACTOR, NOW, "初始录入");
    }
}
