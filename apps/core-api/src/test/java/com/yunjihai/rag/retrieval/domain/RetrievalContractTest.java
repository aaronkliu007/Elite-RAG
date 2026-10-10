package com.yunjihai.rag.retrieval.domain;

import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PrincipalType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalContractTest {

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000001001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000001002");
    private static final UUID SPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000001003");

    @Test
    void queryAndFilterRejectEmptyNullOrMoreThanOneHundredSpaces() {
        var tooManySpaces = new java.util.LinkedHashSet<UUID>();
        for (int index = 0; index < 101; index++) {
            tooManySpaces.add(new UUID(0, index + 1L));
        }

        assertThatThrownBy(() -> new RetrievalQuery(
                REQUEST_ID, USER_ID, Set.of(), "知识检索", Map.of(), 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalQuery(
                REQUEST_ID, USER_ID, tooManySpaces, "知识检索", Map.of(), 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalFilter(
                Set.of(), Set.of(new PrincipalRef(PrincipalType.USER, USER_ID)), true,
                Instant.parse("2026-09-28T01:00:00Z"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalFilter(
                tooManySpaces, Set.of(new PrincipalRef(PrincipalType.USER, USER_ID)), true,
                Instant.parse("2026-09-28T01:00:00Z"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryAndFilterOwnImmutableMultiSpaceScope() {
        var secondSpace = UUID.fromString("00000000-0000-0000-0000-000000001004");
        var spaces = new java.util.LinkedHashSet<>(Set.of(SPACE_ID, secondSpace));
        var query = new RetrievalQuery(
                REQUEST_ID, USER_ID, spaces, "知识检索", Map.of(), 8);
        var filter = new RetrievalFilter(
                spaces, Set.of(new PrincipalRef(PrincipalType.USER, USER_ID)), true,
                Instant.parse("2026-09-28T01:00:00Z"), Map.of());

        spaces.clear();

        assertThat(query.spaceIds()).containsExactlyInAnyOrder(SPACE_ID, secondSpace);
        assertThat(filter.spaceIds()).containsExactlyInAnyOrder(SPACE_ID, secondSpace);
        assertThatThrownBy(() -> query.spaceIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsBlankOrOverlongQueryText() {
        assertThatThrownBy(() -> query(" \t\r\n ", Map.of(), 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("知".repeat(2001), Map.of(), 8))
                .isInstanceOf(IllegalArgumentException.class);

        var normalized = query("  ＡＩ\t知识\n检索  ", Map.of(), 8);

        assertThat(normalized.queryText()).isEqualTo("AI 知识 检索");
        assertThat(query("知".repeat(2000), Map.of(), 8).queryText()).hasSize(2000);
    }

    @Test
    void rejectsUnsafeMetadataAndEvidenceLimits() {
        var tooMany = new LinkedHashMap<String, String>();
        for (int index = 0; index < 21; index++) {
            tooMany.put("key-" + index, "value");
        }

        assertThatThrownBy(() -> query("知识检索", tooMany, 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("知识检索", Map.of(" ", "value"), 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("知识检索", Map.of("k".repeat(65), "value"), 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("知识检索", Map.of("key", ""), 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("知识检索", Map.of("key", "值".repeat(257)), 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("知识检索", Map.of(), 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("知识检索", Map.of(), 16))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(query("知识检索", Map.of("year", "2026"), 8).requestedEvidenceCount())
                .isEqualTo(8);
        assertThat(query("知识检索", Map.of(), 15).requestedEvidenceCount()).isEqualTo(15);
    }

    @Test
    void defaultsPinAllRetrievalLimits() {
        var policy = RetrievalPolicy.defaults();

        assertThat(policy.lexicalLimit()).isEqualTo(50);
        assertThat(policy.vectorLimit()).isEqualTo(50);
        assertThat(policy.fusedLimit()).isEqualTo(30);
        assertThat(policy.rrfK()).isEqualTo(60);
        assertThat(policy.maxPerDocument()).isEqualTo(3);
        assertThat(policy.nearDuplicateThreshold()).isEqualTo(0.85);
        assertThat(policy.recallTimeout()).isEqualTo(Duration.ofMillis(1200));
        assertThat(policy.rerankTimeout()).isEqualTo(Duration.ofMillis(800));
        assertThat(policy.minEvidenceCount()).isEqualTo(8);
        assertThat(policy.maxEvidenceCount()).isEqualTo(15);
    }

    @Test
    void queryDoesNotExposePrincipalOrSecurityTimeFields() {
        assertThat(RetrievalQuery.class.getRecordComponents())
                .extracting(component -> component.getName().toLowerCase())
                .noneMatch(name -> name.contains("principal")
                        || name.equals("startedat")
                        || name.equals("requestedat")
                        || name.equals("authorizationat"));
    }

    @Test
    void queryContextAndFilterOwnImmutableSecurityInputs() {
        var metadata = new LinkedHashMap<>(Map.of("year", "2026"));
        var principals = new java.util.LinkedHashSet<>(Set.of(
                new PrincipalRef(PrincipalType.USER, USER_ID)));
        var query = query("知识检索", metadata, 8);
        var startedAt = Instant.parse("2026-09-28T01:00:00Z");
        var context = new RetrievalContext(startedAt, principals);
        var filter = new RetrievalFilter(Set.of(SPACE_ID), principals, true, startedAt, metadata);

        metadata.put("industry", "software");
        principals.add(new PrincipalRef(PrincipalType.ROLE, UUID.randomUUID()));

        assertThat(query.metadataFilter()).containsExactlyEntriesOf(Map.of("year", "2026"));
        assertThat(context.effectivePrincipals()).hasSize(1);
        assertThat(filter.effectivePrincipals()).hasSize(1);
        assertThat(filter.metadataFilter()).containsExactlyEntriesOf(Map.of("year", "2026"));
        assertThatThrownBy(() -> query.metadataFilter().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> filter.effectivePrincipals().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new RetrievalFilter(
                Set.of(SPACE_ID), Set.of(new PrincipalRef(PrincipalType.USER, USER_ID)),
                false, startedAt, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyRejectsUnsafeOrInconsistentLimits() {
        assertThatThrownBy(() -> new RetrievalPolicy(
                50, 50, 101, 60, 3, 0.85,
                Duration.ofMillis(1200), Duration.ofMillis(800), 8, 15))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalPolicy(
                50, 50, 30, 0, 3, 0.85,
                Duration.ofMillis(1200), Duration.ofMillis(800), 8, 15))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalPolicy(
                50, 50, 30, 60, 3, Double.NaN,
                Duration.ofMillis(1200), Duration.ofMillis(800), 8, 15))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalPolicy(
                50, 50, 30, 60, 3, 0.85,
                Duration.ZERO, Duration.ofMillis(800), 8, 15))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalPolicy(
                50, 50, 30, 60, 3, 0.85,
                Duration.ofMillis(1200), Duration.ofMillis(800), 0, 15))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceLocatorsRejectInventedOrInvalidCoordinates() {
        assertThatThrownBy(() -> new PageLocator(0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NormalizedBoundingBox(-0.01, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NormalizedBoundingBox(0.5, 0, 0.5, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NormalizedBoundingBox(0, 0.8, 1, 0.7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NormalizedBoundingBox(0, 0, Double.NaN, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlideLocator(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TableLocator(" ", 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TableLocator("报价", 2, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TableLocator("报价", 1, 1, 2, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MarkdownLocator(List.of(), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceLocatorsPreserveValidatedLocationsImmutably() {
        var headings = new java.util.ArrayList<>(List.of("总体方案", "部署拓扑"));
        SourceLocator page = new PageLocator(
                3, new NormalizedBoundingBox(0.1, 0.2, 0.8, 0.9));
        SourceLocator slide = new SlideLocator(5);
        SourceLocator table = new TableLocator("报价", 2, 6, 1, 4);
        var markdown = new MarkdownLocator(headings, "node-7");

        headings.add("被调用方修改");

        assertThat(page).isInstanceOf(PageLocator.class);
        assertThat(slide).isInstanceOf(SlideLocator.class);
        assertThat(table).isInstanceOf(TableLocator.class);
        assertThat(markdown.headingPath()).containsExactly("总体方案", "部署拓扑");
        assertThatThrownBy(() -> markdown.headingPath().add("修改"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void candidateRejectsMissingOversizedOrNonFiniteRecallData() {
        var locator = new PageLocator(1, null);

        assertThatThrownBy(() -> new Candidate(
                SPACE_ID, null, UUID.randomUUID(), UUID.randomUUID(), "node", "标题", "正文",
                locator, RecallSource.LEXICAL, 1, 0.8))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> candidate(" ", "标题", "正文", 1, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate("n".repeat(201), "标题", "正文", 1, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate("node", "😀".repeat(501), "正文", 1, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate("node", "标题", "知".repeat(20001), 1, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate("node", "标题", "正文", 0, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate("node", "标题", "正文", 1, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate("node", "标题", "正文", 1, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(candidate("n".repeat(200), "😀".repeat(500), "知".repeat(20000), 1, null)
                .sourceScore()).isNull();
    }

    @Test
    void scoredCandidateAndEvidenceRejectInvalidFinalScoresOrText() {
        var candidate = candidate("node", "标题", "正文", 1, 0.8);

        assertThatThrownBy(() -> new ScoredCandidate(
                candidate, Double.NaN, 1, Set.of(RecallSource.LEXICAL)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoredCandidate(
                candidate, 0.5, 0, Set.of(RecallSource.LEXICAL)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoredCandidate(candidate, 0.5, 1, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoredCandidate(
                candidate, 0.5, 1, Set.of(RecallSource.VECTOR)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Evidence(
                candidate.spaceId(), UUID.randomUUID(), candidate.documentId(), candidate.versionId(), candidate.nodeId(),
                candidate.title(), "知".repeat(1201), 0.5, candidate.locator()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Evidence(
                candidate.spaceId(), UUID.randomUUID(), candidate.documentId(), candidate.versionId(), candidate.nodeId(),
                candidate.title(), "摘要", Double.NEGATIVE_INFINITY, candidate.locator()))
                .isInstanceOf(IllegalArgumentException.class);

        var evidence = new Evidence(
                candidate.spaceId(), UUID.randomUUID(), candidate.documentId(), candidate.versionId(), candidate.nodeId(),
                candidate.title(), "😀".repeat(1200), 0.5, candidate.locator());
        assertThat(evidence.excerpt()).hasSize(2400);
    }

    @Test
    void evidencePackOwnsResultsAndEnforcesStatusInvariants() {
        var candidate = candidate("node", "标题", "正文", 1, 0.8);
        var evidence = new Evidence(
                candidate.spaceId(), UUID.randomUUID(), candidate.documentId(), candidate.versionId(), candidate.nodeId(),
                candidate.title(), "摘要", 0.5, candidate.locator());
        var mutableEvidence = new java.util.ArrayList<>(List.of(evidence));
        var reasons = new java.util.LinkedHashSet<>(Set.of(DegradationReason.VECTOR_UNAVAILABLE));
        var stats = new RetrievalStats(2, 0, 2, 1, 1);
        var pack = new EvidencePack(
                REQUEST_ID, mutableEvidence, true, reasons, stats,
                Instant.parse("2026-09-28T02:00:00Z"));

        mutableEvidence.clear();
        reasons.clear();

        assertThat(pack.evidence()).containsExactly(evidence);
        assertThat(pack.degradationReasons()).containsExactly(DegradationReason.VECTOR_UNAVAILABLE);
        assertThatThrownBy(() -> pack.evidence().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new EvidencePack(
                REQUEST_ID, java.util.Collections.nCopies(16, evidence), false, Set.of(), stats,
                Instant.parse("2026-09-28T02:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidencePack(
                REQUEST_ID, List.of(), false, Set.of(DegradationReason.VECTOR_UNAVAILABLE), stats,
                Instant.parse("2026-09-28T02:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalStats(0, 0, 0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RetrievalQuery query(
            String queryText,
            Map<String, String> metadata,
            int requestedEvidenceCount) {
        return new RetrievalQuery(
                REQUEST_ID,
                USER_ID,
                Set.of(SPACE_ID),
                queryText,
                metadata,
                requestedEvidenceCount);
    }

    private static Candidate candidate(
            String nodeId,
            String title,
            String text,
            int sourceRank,
            Double sourceScore) {
        return new Candidate(
                SPACE_ID,
                UUID.fromString("00000000-0000-0000-0000-000000001011"),
                UUID.fromString("00000000-0000-0000-0000-000000001012"),
                UUID.fromString("00000000-0000-0000-0000-000000001013"),
                nodeId,
                title,
                text,
                new PageLocator(1, null),
                RecallSource.LEXICAL,
                sourceRank,
                sourceScore);
    }
}
