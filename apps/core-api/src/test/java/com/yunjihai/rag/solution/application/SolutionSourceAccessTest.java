package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.solution.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SolutionSourceAccessTest {
    private final UUID actor = UUID.randomUUID();
    private final UUID space = UUID.randomUUID();
    private final UUID document = UUID.randomUUID();
    private final UUID version = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-10-28T10:00:00Z");

    @Test void requiresPublishedReadableVersionInSameSpace() {
        var requirement = requirement();
        var accepted = new SolutionSourceAccess((user, target, checked) -> {},
                (user, targetSpace, targetDocument, targetVersion, checked) ->
                        targetSpace.equals(space) && targetDocument.equals(document) && targetVersion.equals(version));
        accepted.requireCurrentSources(actor, space, requirement, at);
        var denied = new SolutionSourceAccess((user, target, checked) -> {},
                (user, targetSpace, targetDocument, targetVersion, checked) -> false);
        assertThatThrownBy(() -> denied.requireCurrentSources(actor, space, requirement, at))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    @Test void authorityFailureCannotValidateSources() {
        var access = new SolutionSourceAccess((user, target, checked) -> {},
                (user, targetSpace, targetDocument, targetVersion, checked) -> {
                    throw new IllegalStateException("authority unavailable");
                });
        assertThatThrownBy(() -> access.requireCurrentSources(actor, space, requirement(), at))
                .isInstanceOf(IllegalStateException.class);
    }

    private RequirementVersion requirement() {
        return new RequirementVersion(UUID.randomUUID(), 1, List.of(new RequirementItem(UUID.randomUUID(),
                RequirementCategory.GOAL, "目标", List.of(new RequirementSource(
                new DocumentVersionRef(document, version), new PageLocator(1, null))))), actor, at, "初始录入");
    }
}
