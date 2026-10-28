package com.yunjihai.rag.solution.domain;

import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SolutionAggregateTest {

    private static final UUID ACTOR_ID = id(1);
    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z");

    @Test
    void normalizesRequirementTextAndDefensivelyCopiesSources() {
        var sources = new ArrayList<RequirementSource>();
        sources.add(source(10));

        var item = new RequirementItem(id(20), RequirementCategory.GOAL,
                "  建设\u3000统一   知识库  ", sources);
        sources.clear();

        assertThat(item.text()).isEqualTo("建设 统一 知识库");
        assertThat(item.sources()).hasSize(1);
        assertThatThrownBy(() -> item.sources().add(source(11)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiresSourcesForKnownRequirementsButAllowsUnknownItemsWithoutOne() {
        assertThatThrownBy(() -> new RequirementItem(
                id(20), RequirementCategory.CONSTRAINT, "必须内网部署", List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        var unknown = new RequirementItem(
                id(21), RequirementCategory.UNKNOWN, "待确认并发量", List.of());
        assertThat(unknown.sources()).isEmpty();
    }

    @Test
    void rejectsNullsBlanksAndDuplicateRequirementIds() {
        assertThatThrownBy(() -> new RequirementItem(null, RequirementCategory.GOAL, "目标", List.of(source(1))))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RequirementItem(id(1), RequirementCategory.GOAL, "　 ", List.of(source(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequirementItem(id(1), RequirementCategory.GOAL, "目标",
                Arrays.asList((RequirementSource) null)))
                .isInstanceOf(NullPointerException.class);

        var duplicate = requirement(id(30), RequirementCategory.GOAL, "目标");
        assertThatThrownBy(() -> new RequirementVersion(
                id(40), 1, List.of(duplicate, duplicate), ACTOR_ID, NOW, "首次整理"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requirementVersionDefensivelyCopiesItemsAndNormalizesReason() {
        var items = new ArrayList<RequirementItem>();
        items.add(requirement(id(30), RequirementCategory.GOAL, "统一知识库"));

        var version = new RequirementVersion(id(40), 1, items, ACTOR_ID, NOW, "  首次　整理 ");
        items.clear();

        assertThat(version.items()).hasSize(1);
        assertThat(version.changeReason()).isEqualTo("首次 整理");
        assertThatThrownBy(() -> version.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void outlineNodeValidatesLevelAndDefensivelyCopiesRiskTypes() {
        var risks = new HashSet<>(Set.of(RiskType.AMOUNT_OR_PRICE));
        var node = new OutlineNode(id(50), "  建设　方案 ", 2, 1, true,
                "  描述   总体方案 ", " 检索　架构 ", risks);
        risks.clear();

        assertThat(node.title()).isEqualTo("建设 方案");
        assertThat(node.riskTypes()).containsExactly(RiskType.AMOUNT_OR_PRICE);
        assertThatThrownBy(() -> node.riskTypes().add(RiskType.DATE_OR_DURATION))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new OutlineNode(id(51), "标题", 0, 1, true,
                "目标", "提示", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutlineNode(id(52), "标题", 5, 1, true,
                "目标", "提示", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outlineRejectsDuplicateIdsNonContiguousOrderAndNoRequiredNode() {
        var first = outline(id(60), 1, true);
        var duplicateId = new OutlineNode(first.sectionId(), "重复", 1, 2, true,
                "重复目标", "重复提示", Set.of());
        assertThatThrownBy(() -> new OutlineVersion(id(70), 1,
                List.of(first, duplicateId), ACTOR_ID, NOW))
                .isInstanceOf(IllegalArgumentException.class);

        var skipped = outline(id(61), 3, true);
        assertThatThrownBy(() -> new OutlineVersion(id(71), 1,
                List.of(first, skipped), ACTOR_ID, NOW))
                .isInstanceOf(IllegalArgumentException.class);

        var optional = outline(id(62), 1, false);
        assertThatThrownBy(() -> new OutlineVersion(id(72), 1,
                List.of(optional), ACTOR_ID, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outlineVersionDefensivelyCopiesNodesAndRejectsNullMembers() {
        var nodes = new ArrayList<OutlineNode>();
        nodes.add(outline(id(60), 1, true));
        var version = new OutlineVersion(id(70), 1, nodes, ACTOR_ID, NOW);
        nodes.clear();

        assertThat(version.nodes()).hasSize(1);
        assertThatThrownBy(() -> version.nodes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new OutlineVersion(id(71), 1,
                Arrays.asList((OutlineNode) null), ACTOR_ID, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void followsConfirmedRequirementsOutlineAdoptionAndFinalizationLifecycle() {
        var requirements = requirements(id(100), 1, "首次需求");
        var outline = outlineVersion(id(101), 1, id(102));
        var solution = Solution.create(id(103), id(104), ACTOR_ID, requirements, NOW);

        assertThat(solution.phase()).isEqualTo(SolutionPhase.REQUIREMENTS_DRAFT);
        solution = solution.confirmRequirements(ACTOR_ID, NOW.plusSeconds(1), solution.revision());
        solution = solution.reviseOutline(outline, solution.revision());
        solution = solution.confirmOutline(ACTOR_ID, NOW.plusSeconds(2), solution.revision());
        solution = solution.adoptSection(id(102), id(105), solution.revision());
        solution = solution.markFinalized(id(106), solution.revision());

        assertThat(solution.phase()).isEqualTo(SolutionPhase.FINALIZED);
        assertThat(solution.finalizedVersionId()).contains(id(106));
        assertThat(solution.adoptedSectionVersionIds()).containsEntry(id(102), id(105));
        assertThat(solution.revision()).isEqualTo(5);
    }

    @Test
    void requiresCurrentConfirmedRequirementsBeforeCreatingOutline() {
        var solution = Solution.create(id(103), id(104), ACTOR_ID,
                requirements(id(100), 1, "首次需求"), NOW);

        assertThatThrownBy(() -> solution.reviseOutline(
                outlineVersion(id(101), 1, id(102)), solution.revision()))
                .isInstanceOf(SolutionLifecycleException.class);
    }

    @Test
    void revisingRequirementsInvalidatesOutlineConfirmationsAndAdoptedSections() {
        var solution = confirmedOutlineSolution();
        solution = solution.adoptSection(id(102), id(105), solution.revision());

        var revised = solution.reviseRequirements(
                requirements(id(110), 2, "客户补充约束"), solution.revision());

        assertThat(revised.phase()).isEqualTo(SolutionPhase.REQUIREMENTS_DRAFT);
        assertThat(revised.currentRequirementVersionId()).isEqualTo(id(110));
        assertThat(revised.currentOutlineVersionId()).isEmpty();
        assertThat(revised.requirementConfirmation()).isEmpty();
        assertThat(revised.outlineConfirmation()).isEmpty();
        assertThat(revised.adoptedSectionVersionIds()).isEmpty();
    }

    @Test
    void revisingOutlineInvalidatesItsConfirmationAndOldSectionPointers() {
        var solution = confirmedOutlineSolution();
        solution = solution.adoptSection(id(102), id(105), solution.revision());

        var revised = solution.reviseOutline(
                outlineVersion(id(111), 2, id(112)), solution.revision());

        assertThat(revised.phase()).isEqualTo(SolutionPhase.OUTLINE_DRAFT);
        assertThat(revised.currentOutlineVersionId()).contains(id(111));
        assertThat(revised.requirementConfirmation()).isPresent();
        assertThat(revised.outlineConfirmation()).isEmpty();
        assertThat(revised.adoptedSectionVersionIds()).isEmpty();
    }

    @Test
    void finalizedSolutionRequiresExplicitNewRevisionBeforeFurtherChanges() {
        var solution = confirmedOutlineSolution();
        solution = solution.adoptSection(id(102), id(105), solution.revision());
        solution = solution.markFinalized(id(106), solution.revision());
        var finalized = solution;

        assertThatThrownBy(() -> finalized.reviseRequirements(
                requirements(id(110), 2, "不允许直接修改"), finalized.revision()))
                .isInstanceOf(SolutionLifecycleException.class);

        var next = finalized.startNewRevision(
                requirements(id(110), 2, "启动新修订"), finalized.revision());
        assertThat(next.solutionRevision()).isEqualTo(2);
        assertThat(next.phase()).isEqualTo(SolutionPhase.REQUIREMENTS_DRAFT);
        assertThat(next.finalizedVersionId()).isEmpty();
        assertThat(next.currentOutlineVersionId()).isEmpty();
    }

    @Test
    void rejectsStaleExpectedRevisionBeforeChangingAggregate() {
        var solution = Solution.create(id(103), id(104), ACTOR_ID,
                requirements(id(100), 1, "首次需求"), NOW);

        assertThatThrownBy(() -> solution.confirmRequirements(ACTOR_ID, NOW, 99))
                .isInstanceOf(ConcurrentSolutionUpdateException.class);
        assertThat(solution.phase()).isEqualTo(SolutionPhase.REQUIREMENTS_DRAFT);
        assertThat(solution.revision()).isZero();
    }

    @Test
    void adoptedSectionPointersAreImmutable() {
        var solution = confirmedOutlineSolution()
                .adoptSection(id(102), id(105), 3);

        assertThatThrownBy(() -> solution.adoptedSectionVersionIds().put(id(120), id(121)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void namedSolutionRestoresCurrentConfirmationWithoutChangingItsRevision() {
        var initial = requirements(id(130), 1, "首次需求");
        var solution = Solution.create(id(131), id(132), ACTOR_ID,
                SolutionName.of("  制造业　知识库方案  "), initial, NOW);
        solution = solution.confirmRequirements(id(133), NOW.plusSeconds(1), 0);
        solution = solution.reviseOutline(outlineVersion(id(134), 1, id(135)), 1);
        var restored = Solution.restore(solution.snapshot());

        assertThat(restored.name().value()).isEqualTo("制造业 知识库方案");
        assertThat(restored.revision()).isEqualTo(2);
        assertThat(restored.phase()).isEqualTo(SolutionPhase.OUTLINE_DRAFT);
        assertThat(restored.requirementConfirmation()).contains(solution.requirementConfirmation().orElseThrow());
        assertThat(restored.currentOutlineVersionId()).contains(id(134));
    }

    @Test
    void restoreRejectsConfirmationPointingAtDifferentRequirementVersion() {
        var initial = requirements(id(140), 1, "首次需求");
        var solution = Solution.create(id(141), id(142), ACTOR_ID,
                SolutionName.of("测试方案"), initial, NOW);
        var invalid = new SolutionSnapshot(solution.id(), solution.spaceId(), solution.createdBy(),
                solution.createdAt(), solution.name(), solution.solutionRevision(), solution.revision(),
                solution.currentRequirementVersionId(), Optional.empty(),
                Optional.of(new VersionConfirmation(id(143), ACTOR_ID, NOW)), Optional.empty(),
                Map.of(), SolutionPhase.REQUIREMENTS_CONFIRMED, Optional.empty());
        assertThatThrownBy(() -> Solution.restore(invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restoreRejectsConfirmedPhaseWithoutMatchingConfirmation() {
        var initial = requirements(id(150), 1, "首次需求");
        var solution = Solution.create(id(151), id(152), ACTOR_ID,
                SolutionName.of("测试方案"), initial, NOW);
        var invalid = new SolutionSnapshot(solution.id(), solution.spaceId(), solution.createdBy(),
                solution.createdAt(), solution.name(), solution.solutionRevision(), solution.revision(),
                solution.currentRequirementVersionId(), Optional.empty(), Optional.empty(),
                Optional.empty(), Map.of(), SolutionPhase.REQUIREMENTS_CONFIRMED, Optional.empty());
        assertThatThrownBy(() -> Solution.restore(invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void solutionNameRejectsBlankAndOverlongValues() {
        assertThatThrownBy(() -> SolutionName.of(" 　"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SolutionName.of("方".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RequirementSource source(int suffix) {
        return new RequirementSource(
                new DocumentVersionRef(id(suffix), id(suffix + 100)),
                new MarkdownLocator(List.of("客户需求"), "node-" + suffix));
    }

    private static RequirementItem requirement(UUID itemId, RequirementCategory category, String text) {
        return new RequirementItem(itemId, category, text,
                category == RequirementCategory.UNKNOWN ? List.of() : List.of(source(itemId.hashCode() & 255)));
    }

    private static OutlineNode outline(UUID sectionId, int order, boolean required) {
        return new OutlineNode(sectionId, "章节 " + order, 1, order, required,
                "章节目标 " + order, "检索提示 " + order, Set.of());
    }

    private static RequirementVersion requirements(UUID versionId, long number, String reason) {
        return new RequirementVersion(versionId, number,
                List.of(requirement(id((int) number + 200), RequirementCategory.GOAL, "建设企业知识库")),
                ACTOR_ID, NOW, reason);
    }

    private static OutlineVersion outlineVersion(UUID versionId, long number, UUID sectionId) {
        return new OutlineVersion(versionId, number,
                List.of(outline(sectionId, 1, true)), ACTOR_ID, NOW);
    }

    private static Solution confirmedOutlineSolution() {
        var solution = Solution.create(id(103), id(104), ACTOR_ID,
                requirements(id(100), 1, "首次需求"), NOW);
        solution = solution.confirmRequirements(ACTOR_ID, NOW.plusSeconds(1), solution.revision());
        solution = solution.reviseOutline(
                outlineVersion(id(101), 1, id(102)), solution.revision());
        return solution.confirmOutline(ACTOR_ID, NOW.plusSeconds(2), solution.revision());
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }
}
