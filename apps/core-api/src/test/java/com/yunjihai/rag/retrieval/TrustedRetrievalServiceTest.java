package com.yunjihai.rag.retrieval;

import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PrincipalType;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.Candidate;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.retrieval.domain.RecallSource;
import com.yunjihai.rag.retrieval.domain.RetrievalPolicy;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import com.yunjihai.rag.retrieval.search.HybridRecallService;
import com.yunjihai.rag.retrieval.search.LexicalRetriever;
import com.yunjihai.rag.retrieval.search.RetrievalUnavailableException;
import com.yunjihai.rag.retrieval.search.VectorRetriever;
import com.yunjihai.rag.retrieval.selection.EvidenceSelectionService;
import com.yunjihai.rag.retrieval.selection.RerankScore;
import com.yunjihai.rag.retrieval.selection.RerankingService;
import com.yunjihai.rag.retrieval.selection.RetrievalAuthorizationPort;
import com.yunjihai.rag.qa.sanitization.RegexSensitiveContentSanitizer;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedRetrievalServiceTest {

    private static final UUID REQUEST_ID = uuid(1);
    private static final UUID USER_ID = uuid(2);
    private static final UUID SPACE_ID = uuid(3);
    private static final Instant NOW = Instant.parse("2026-09-28T04:00:00Z");

    @Test
    void sanitizesQuestionAndAuthorizedCandidatesBeforeExternalRecallAndRerank() {
        var observedRecallQuery = new AtomicReference<String>();
        var observedRerankQuery = new AtomicReference<String>();
        var observedRerankText = new AtomicReference<String>();
        var candidate = candidate(9, 109, RecallSource.LEXICAL, 1,
                "联系人 13812345678");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var policy = RetrievalPolicy.defaults();
            var service = new TrustedRetrievalService(
                    (userId, at) -> Set.of(new PrincipalRef(PrincipalType.USER, userId)),
                    new HybridRecallService(
                            (query, filter, limit) -> {
                                observedRecallQuery.set(query);
                                return List.of(candidate);
                            },
                            (query, filter, limit) -> List.of(), executor, policy),
                    new RerankingService((query, candidates) -> {
                        observedRerankQuery.set(query);
                        observedRerankText.set(candidates.getFirst().candidate().text());
                        return candidates.stream().map(value -> new RerankScore(
                                value.candidate().candidateId(), value.score())).toList();
                    }, executor, policy),
                    (userId, requestedBySpace, at) -> flatten(requestedBySpace),
                    new EvidenceSelectionService(policy), at -> uuid(9090), policy,
                    Clock.fixed(NOW, ZoneOffset.UTC), command -> null,
                    new RegexSensitiveContentSanitizer(Set.of()));

            service.retrieve(new RetrievalQuery(
                    REQUEST_ID, USER_ID, Set.of(SPACE_ID),
                    "查询 13812345678", Map.of(), 8));

            assertThat(observedRecallQuery.get()).isEqualTo("查询 [PHONE]");
            assertThat(observedRerankQuery.get()).isEqualTo("查询 [PHONE]");
            assertThat(observedRerankText.get()).isEqualTo("联系人 [PHONE]");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void blocksUnsafeQuestionBeforeAnyRecallBranchRuns() {
        var recallCalls = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var policy = RetrievalPolicy.defaults();
            var service = new TrustedRetrievalService(
                    (userId, at) -> Set.of(new PrincipalRef(PrincipalType.USER, userId)),
                    new HybridRecallService(
                            (query, filter, limit) -> { recallCalls.incrementAndGet(); return List.of(); },
                            (query, filter, limit) -> { recallCalls.incrementAndGet(); return List.of(); },
                            executor, policy),
                    new RerankingService((query, candidates) -> List.of(), executor, policy),
                    (userId, requestedBySpace, at) -> flatten(requestedBySpace),
                    new EvidenceSelectionService(policy), at -> uuid(9091), policy,
                    Clock.fixed(NOW, ZoneOffset.UTC), command -> null,
                    new RegexSensitiveContentSanitizer(Set.of()));

            assertThatThrownBy(() -> service.retrieve(new RetrievalQuery(
                    REQUEST_ID, USER_ID, Set.of(SPACE_ID),
                    "-----BEGIN PRIVATE KEY-----secret-----END PRIVATE KEY-----",
                    Map.of(), 8)))
                    .isInstanceOf(RetrievalOutboundPolicyException.class);
            assertThat(recallCalls).hasValue(0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void retainsOnlyReauthorizedCandidatesForConflictScreeningBeforeSelection() {
        var candidates = new ArrayList<Candidate>();
        for (int index = 1; index <= 13; index++) {
            candidates.add(candidate(index, 100 + index, RecallSource.LEXICAL, index,
                    "第" + index + "个不同候选的正文"));
        }
        var executor = Executors.newFixedThreadPool(2);
        try {
            var policy = RetrievalPolicy.defaults();
            var ids = new AtomicInteger(5000);
            var service = new TrustedRetrievalService(
                    (userId, at) -> Set.of(new PrincipalRef(PrincipalType.USER, userId)),
                    new HybridRecallService((query, filter, limit) -> candidates,
                            (query, filter, limit) -> List.of(), executor, policy),
                    new RerankingService((query, values) -> values.stream()
                            .map(value -> new RerankScore(value.candidate().candidateId(), value.score())).toList(),
                            executor, policy),
                    (userId, requested, at) -> flatten(requested).stream()
                            .filter(version -> !version.documentId().equals(uuid(113)))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    new EvidenceSelectionService(policy), at -> uuid(ids.incrementAndGet()), policy,
                    Clock.fixed(NOW, ZoneOffset.UTC), command -> null);

            var result = service.retrieve(new RetrievalQuery(
                    REQUEST_ID, USER_ID, Set.of(SPACE_ID), "证据", Map.of(), 8));

            assertThat(result.evidence()).hasSize(8);
            assertThat(result.conflictScreeningEvidence()).hasSize(12)
                    .extracting(item -> item.documentId()).doesNotContain(uuid(113))
                    .contains(uuid(111), uuid(112));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void groupsFinalAuthorizationByCandidateSpaceAndPreservesSpaceInEvidence() {
        var secondSpace = uuid(4);
        var first = new Candidate(
                SPACE_ID, uuid(10), uuid(110), uuid(1110), "node-10", "标题-10", "正文甲",
                new PageLocator(1, null), RecallSource.LEXICAL, 1, 1.0);
        var second = new Candidate(
                secondSpace, uuid(20), uuid(120), uuid(1120), "node-20", "标题-20", "正文乙",
                new PageLocator(2, null), RecallSource.LEXICAL, 2, 0.8);
        var authorizationRequest = new AtomicReference<Map<UUID, Set<DocumentVersionRef>>>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var policy = RetrievalPolicy.defaults();
            var recall = new HybridRecallService(
                    (query, filter, limit) -> List.of(first, second),
                    (query, filter, limit) -> List.of(),
                    executor,
                    policy);
            var reranking = new RerankingService(
                    (query, candidates) -> candidates.stream()
                            .map(candidate -> new RerankScore(
                                    candidate.candidate().candidateId(), candidate.score()))
                            .toList(),
                    executor,
                    policy);
            var service = new TrustedRetrievalService(
                    (userId, at) -> Set.of(new PrincipalRef(PrincipalType.USER, userId)),
                    recall,
                    reranking,
                    (userId, versionsBySpace, checkedAt) -> {
                        authorizationRequest.set(versionsBySpace);
                        return versionsBySpace.values().stream()
                                .flatMap(Set::stream)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    },
                    new EvidenceSelectionService(policy),
                    at -> uuid(9000),
                    policy,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    command -> null);

            var result = service.retrieve(new RetrievalQuery(
                    REQUEST_ID, USER_ID, Set.of(SPACE_ID, secondSpace),
                    "企业知识检索", Map.of(), 8));

            assertThat(authorizationRequest.get()).containsOnlyKeys(SPACE_ID, secondSpace);
            assertThat(authorizationRequest.get().get(SPACE_ID)).containsExactly(
                    new DocumentVersionRef(first.documentId(), first.versionId()));
            assertThat(authorizationRequest.get().get(secondSpace)).containsExactly(
                    new DocumentVersionRef(second.documentId(), second.versionId()));
            assertThat(result.evidence())
                    .extracting(evidence -> evidence.spaceId())
                    .containsExactly(SPACE_ID, secondSpace);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void orchestratesHealthyRetrievalIntoAuthorizedEvidence() {
        var order = new ConcurrentLinkedQueue<String>();
        var authorizationRequest = new AtomicReference<Set<DocumentVersionRef>>();
        var authorizationTime = new AtomicReference<Instant>();
        var auditCommands = new ArrayList<AuditCommand>();
        var idSequence = new AtomicInteger();
        var longText = "😀".repeat(1300);
        var lexical = candidate(10, 110, RecallSource.LEXICAL, 1, longText);
        var vector = candidate(20, 120, RecallSource.VECTOR, 1, "向量证据正文");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var recall = new HybridRecallService(
                    (query, filter, limit) -> {
                        order.add("lexical");
                        return List.of(lexical);
                    },
                    (query, filter, limit) -> {
                        order.add("vector");
                        return List.of(vector);
                    },
                    executor,
                    RetrievalPolicy.defaults());
            var reranking = new RerankingService(
                    (query, candidates) -> {
                        order.add("rerank");
                        return List.of(
                                new RerankScore(vector.candidateId(), 0.9),
                                new RerankScore(lexical.candidateId(), 0.8));
                    },
                    executor,
                    RetrievalPolicy.defaults());
            RetrievalAuthorizationPort authorization = (userId, requestedBySpace, checkedAt) -> {
                order.add("authorize");
                var requested = flatten(requestedBySpace);
                authorizationRequest.set(requested);
                authorizationTime.set(checkedAt);
                return requested;
            };
            var service = new TrustedRetrievalService(
                    (userId, at) -> {
                        order.add("principal");
                        return Set.of(
                                new PrincipalRef(PrincipalType.USER, userId),
                                new PrincipalRef(PrincipalType.ROLE, uuid(4)));
                    },
                    recall,
                    reranking,
                    authorization,
                    new EvidenceSelectionService(RetrievalPolicy.defaults()),
                    at -> {
                        order.add("id");
                        return uuid(9000 + idSequence.getAndIncrement());
                    },
                    RetrievalPolicy.defaults(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    command -> {
                        order.add("audit");
                        auditCommands.add(command);
                        return null;
                    });

            var result = service.retrieve(query());

            assertThat(result.evidence())
                    .extracting(evidence -> evidence.documentId())
                    .containsExactly(vector.documentId(), lexical.documentId());
            var truncated = result.evidence().get(1).excerpt();
            assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(1200);
            assertThat(result.evidence())
                    .extracting(evidence -> evidence.evidenceId())
                    .containsExactly(uuid(9000), uuid(9001));
            assertThat(result.stats().lexicalCount()).isEqualTo(1);
            assertThat(result.stats().vectorCount()).isEqualTo(1);
            assertThat(result.stats().fusedCount()).isEqualTo(2);
            assertThat(result.stats().reauthorizedCount()).isEqualTo(2);
            assertThat(result.stats().selectedCount()).isEqualTo(2);
            assertThat(authorizationRequest.get()).containsExactlyInAnyOrder(
                    new DocumentVersionRef(lexical.documentId(), lexical.versionId()),
                    new DocumentVersionRef(vector.documentId(), vector.versionId()));
            assertThat(authorizationTime.get()).isEqualTo(NOW);

            var observed = List.copyOf(order);
            assertThat(observed.getFirst()).isEqualTo("principal");
            assertThat(observed.indexOf("rerank")).isGreaterThan(observed.indexOf("lexical"));
            assertThat(observed.indexOf("rerank")).isGreaterThan(observed.indexOf("vector"));
            assertThat(observed.indexOf("authorize")).isGreaterThan(observed.indexOf("vector"));
            assertThat(observed.indexOf("rerank")).isGreaterThan(observed.indexOf("authorize"));
            assertThat(observed.indexOf("id")).isGreaterThan(observed.indexOf("rerank"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void principalResolutionFailsClosedBeforeAnyRetrievalWork() {
        var withNull = new HashSet<PrincipalRef>();
        withNull.add(null);
        List<PrincipalResolverPort> invalidResolvers = List.of(
                (userId, at) -> Set.of(),
                (userId, at) -> Set.of(new PrincipalRef(PrincipalType.ROLE, uuid(77))),
                (userId, at) -> null,
                (userId, at) -> withNull,
                (userId, at) -> {
                    throw new IllegalStateException("主体服务秘密错误");
                });
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (var resolver : invalidResolvers) {
                var recallCalls = new AtomicInteger();
                var rerankCalls = new AtomicInteger();
                var authorizationCalls = new AtomicInteger();
                var idCalls = new AtomicInteger();
                var service = serviceForPrincipalTest(
                        resolver,
                        recallCalls,
                        rerankCalls,
                        authorizationCalls,
                        idCalls,
                        executor);

                assertThatThrownBy(() -> service.retrieve(query()))
                        .isInstanceOf(RetrievalAuthorizationUnavailableException.class)
                        .hasMessageNotContaining("秘密");
                assertThat(recallCalls).hasValue(0);
                assertThat(rerankCalls).hasValue(0);
                assertThat(authorizationCalls).hasValue(0);
                assertThat(idCalls).hasValue(0);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void authorizesBeforeAndAfterRerankingAndFiltersDeniedVersions() {
        var candidates = List.of(
                candidate(41, 141, RecallSource.LEXICAL, 1, "甲甲甲甲"),
                candidate(42, 142, RecallSource.LEXICAL, 2, "乙乙乙乙"),
                candidate(43, 143, RecallSource.LEXICAL, 3, "丙丙丙丙"),
                candidate(44, 144, RecallSource.LEXICAL, 4, "丁丁丁丁"),
                candidate(45, 145, RecallSource.LEXICAL, 5, "戊戊戊戊"));
        var start = Instant.parse("2026-09-28T05:00:00Z");
        var checked = start.plusSeconds(5);
        var completed = checked.plusSeconds(1);
        var allowed = new DocumentVersionRef(
                candidates.getFirst().documentId(), candidates.getFirst().versionId());
        try (var harness = new AuthorizationHarness(
                candidates,
                requested -> Set.of(allowed),
                new SequenceClock(List.of(start, checked, completed)))) {

            var result = harness.service.retrieve(query());

            assertThat(harness.authorizationCalls).hasValue(2);
            assertThat(harness.requested.get()).containsExactly(allowed);
            assertThat(harness.checkedAt.get()).isEqualTo(completed);
            assertThat(result.evidence())
                    .extracting(evidence -> evidence.documentId())
                    .containsExactly(candidates.getFirst().documentId());
            assertThat(result.stats().reauthorizedCount()).isEqualTo(1);
            assertThat(harness.idCalls).hasValue(1);
            assertThat(harness.rerankedDocumentIds.get())
                    .containsExactly(candidates.getFirst().documentId());
        }

        try (var denied = new AuthorizationHarness(
                candidates,
                requested -> Set.of(),
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            var empty = denied.service.retrieve(query());

            assertThat(denied.authorizationCalls).hasValue(2);
            assertThat(empty.evidence()).isEmpty();
            assertThat(empty.stats().reauthorizedCount()).isZero();
            assertThat(denied.idCalls).hasValue(0);
            assertThat(denied.rerankedDocumentIds.get()).isEmpty();
        }
    }

    @Test
    void filtersUnselectedDocumentAndVersionBeforeExternalReranking() {
        var selected = candidate(41, 141, RecallSource.LEXICAL, 1, "选中的内容");
        var other = candidate(42, 142, RecallSource.LEXICAL, 2, "不应外发的内容");
        try (var harness = new AuthorizationHarness(List.of(selected, other), requested -> requested,
                Clock.fixed(Instant.parse("2026-09-28T05:00:00Z"), ZoneOffset.UTC))) {
            var request = new RetrievalQuery(REQUEST_ID, USER_ID, Set.of(SPACE_ID), "业务需求",
                    Map.of("documentId", selected.documentId().toString(),
                            "versionId", selected.versionId().toString()), 8);
            var result = harness.service.retrieve(request);
            assertThat(harness.rerankedDocumentIds.get()).containsExactly(selected.documentId());
            assertThat(result.evidence()).extracting(item -> item.documentId()).containsExactly(selected.documentId());
        }
    }

    @Test
    void reauthorizesAgainstMutableAuthorityAfterRecallStateChanges() {
        var healthy = candidate(71, 171, RecallSource.LEXICAL, 1, "健康证据");
        var revoked = candidate(72, 172, RecallSource.LEXICAL, 2, "撤权证据");
        var replaced = candidate(73, 173, RecallSource.LEXICAL, 3, "换版证据");
        var archived = candidate(74, 174, RecallSource.LEXICAL, 4, "归档证据");
        var expired = candidate(75, 175, RecallSource.LEXICAL, 5, "过期证据");
        var membershipRemoved = candidate(76, 176, RecallSource.LEXICAL, 6, "成员移除证据");
        var candidates = List.of(
                healthy, revoked, replaced, archived, expired, membershipRemoved);
        var start = Instant.parse("2026-09-28T05:30:00Z");
        var checked = start.plusSeconds(5);
        var completed = checked.plusSeconds(1);
        var roleId = uuid(177);
        var authority = new MutableAuthority(
                USER_ID, SPACE_ID, roleId, start, candidates, expired, membershipRemoved);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var policy = RetrievalPolicy.defaults();
            var recall = new HybridRecallService(
                    (query, filter, limit) -> candidates,
                    (query, filter, limit) -> List.of(),
                    executor,
                    policy);
            var reranking = new RerankingService(
                    (query, values) -> values.stream()
                                .map(value -> new RerankScore(
                                        value.candidate().candidateId(), value.score()))
                                .toList(),
                    executor,
                    policy);
            var service = new TrustedRetrievalService(
                    (userId, at) -> Set.of(
                            new PrincipalRef(PrincipalType.USER, userId),
                            new PrincipalRef(PrincipalType.ROLE, roleId)),
                    recall,
                    reranking,
                    (userId, versionsBySpace, at) -> {
                        authority.changeAfterRecall(
                                revoked, replaced, archived, membershipRemoved);
                        return authority.authorize(userId, versionsBySpace, at);
                    },
                    new EvidenceSelectionService(policy),
                    at -> uuid(9400),
                    policy,
                    new SequenceClock(List.of(start, checked, completed)),
                    command -> null);

            var result = service.retrieve(query());

            assertThat(authority.authorizationCalls()).isEqualTo(2);
            assertThat(authority.lastUserId()).isEqualTo(USER_ID);
            assertThat(authority.lastSpaceId()).isEqualTo(SPACE_ID);
            assertThat(authority.lastCheckedAt()).isEqualTo(completed);
            assertThat(result.evidence())
                    .extracting(evidence -> evidence.documentId())
                    .containsExactly(healthy.documentId());
            assertThat(result.evidence())
                    .extracting(evidence -> evidence.documentId())
                    .doesNotContain(
                            revoked.documentId(),
                            replaced.documentId(),
                            archived.documentId(),
                            expired.documentId(),
                            membershipRemoved.documentId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidAuthorizationResultsFailClosedBeforeEvidenceGeneration() {
        var candidates = List.of(
                candidate(51, 151, RecallSource.LEXICAL, 1, "授权候选甲"),
                candidate(52, 152, RecallSource.LEXICAL, 2, "授权候选乙"));
        List<Function<Set<DocumentVersionRef>, Set<DocumentVersionRef>>> invalidResults = List.of(
                requested -> {
                    var withUnknown = new LinkedHashSet<>(requested);
                    withUnknown.add(new DocumentVersionRef(uuid(9991), uuid(9992)));
                    return withUnknown;
                },
                requested -> null,
                requested -> {
                    throw new IllegalStateException("授权源秘密错误和正文");
                });

        for (var invalid : invalidResults) {
            try (var harness = new AuthorizationHarness(
                    candidates,
                    invalid,
                    Clock.fixed(NOW, ZoneOffset.UTC))) {
                assertThatThrownBy(() -> harness.service.retrieve(query()))
                        .isInstanceOf(RetrievalAuthorizationUnavailableException.class)
                        .hasMessageNotContaining("秘密")
                        .hasMessageNotContaining("正文");
                assertThat(harness.authorizationCalls).hasValue(1);
                assertThat(harness.idCalls).hasValue(0);
                assertThat(harness.auditCommands).singleElement().satisfies(command -> {
                    assertThat(command.outcome()).isEqualTo(AuditOutcome.FAILED);
                    assertBodyFreeMetadata(command);
                });
            }
        }
    }

    @Test
    void auditsSuccessfulEmptyAndDegradedRetrievalWithoutSensitiveText() {
        var candidate = candidate(61, 161, RecallSource.LEXICAL, 1, "审计候选秘密正文");
        var start = Instant.parse("2026-09-28T06:00:00Z");
        var checked = start.plusSeconds(1);
        var completed = start.plusSeconds(2);
        try (var healthy = new AuthorizationHarness(
                List.of(candidate),
                requested -> requested,
                new SequenceClock(List.of(start, checked, completed)))) {
            healthy.service.retrieve(query());

            assertSuccessfulAudit(healthy.auditCommands.getFirst(), "1", "1", "");
            assertThat(healthy.auditCommands.getFirst().metadata().get("durationMs"))
                    .isEqualTo("2000");
        }

        try (var empty = new AuthorizationHarness(
                List.of(candidate),
                requested -> Set.of(),
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            empty.service.retrieve(query());

            assertSuccessfulAudit(empty.auditCommands.getFirst(), "0", "0", "");
        }

        var degradedAudits = new ArrayList<AuditCommand>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var service = trustedService(
                    (query, filter, limit) -> List.of(candidate),
                    (query, filter, limit) -> {
                        throw new IllegalStateException("向量供应商秘密错误");
                    },
                    (userId, requestedBySpace, at) -> flatten(requestedBySpace),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    command -> {
                        degradedAudits.add(command);
                        return null;
                    },
                    executor);

            var result = service.retrieve(query());

            assertThat(result.degraded()).isTrue();
            assertSuccessfulAudit(
                    degradedAudits.getFirst(), "1", "1", "VECTOR_UNAVAILABLE");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failureAuditCannotMaskTheBusinessFailureAndSuccessRequiresAudit() {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var failedAudits = new ArrayList<AuditCommand>();
            var unavailable = trustedService(
                    (query, filter, limit) -> {
                        throw new IllegalStateException("全文秘密错误");
                    },
                    (query, filter, limit) -> {
                        throw new IllegalStateException("向量秘密错误");
                    },
                    (userId, requestedBySpace, at) -> flatten(requestedBySpace),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    command -> {
                        failedAudits.add(command);
                        return null;
                    },
                    executor);

            assertThatThrownBy(() -> unavailable.retrieve(query()))
                    .isInstanceOf(RetrievalUnavailableException.class)
                    .hasMessageNotContaining("秘密");
            assertThat(failedAudits).singleElement().satisfies(command -> {
                assertThat(command.action()).isEqualTo(AuditAction.RETRIEVE);
                assertThat(command.outcome()).isEqualTo(AuditOutcome.FAILED);
                assertBodyFreeMetadata(command);
            });

            var candidate = candidate(62, 162, RecallSource.LEXICAL, 1, "成功正文");
            var successAuditFailure = trustedService(
                    (query, filter, limit) -> List.of(candidate),
                    (query, filter, limit) -> List.of(),
                    (userId, requestedBySpace, at) -> flatten(requestedBySpace),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    command -> {
                        throw new IllegalStateException("审计不可用");
                    },
                    executor);
            assertThatThrownBy(() -> successAuditFailure.retrieve(query()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("审计不可用");

            var failedAuditFailure = trustedService(
                    (query, filter, limit) -> {
                        throw new IllegalStateException("全文秘密错误");
                    },
                    (query, filter, limit) -> {
                        throw new IllegalStateException("向量秘密错误");
                    },
                    (userId, requestedBySpace, at) -> flatten(requestedBySpace),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    command -> {
                        throw new IllegalStateException("失败审计不可用");
                    },
                    executor);
            var thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> failedAuditFailure.retrieve(query()));
            assertThat(thrown).isInstanceOf(RetrievalUnavailableException.class);
            assertThat(thrown.getSuppressed()).hasSize(1);
            assertThat(thrown.getSuppressed()[0]).isInstanceOf(IllegalStateException.class);
            assertThat(thrown.getSuppressed()[0].getMessage()).isEqualTo("失败审计不可用");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertSuccessfulAudit(
            AuditCommand command,
            String reauthorizedCount,
            String selectedCount,
            String degradationReasons) {
        assertThat(command.actorId()).isEqualTo(USER_ID);
        assertThat(command.action()).isEqualTo(AuditAction.RETRIEVE);
        assertThat(command.resourceType()).isEqualTo("RETRIEVAL");
        assertThat(command.resourceId()).isEqualTo(REQUEST_ID);
        assertThat(command.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
        assertThat(command.metadata().get("queryCodePoints")).matches("[1-9][0-9]*");
        assertThat(command.metadata()).containsEntry("reauthorizedCount", reauthorizedCount);
        assertThat(command.metadata()).containsEntry("selectedCount", selectedCount);
        assertThat(command.metadata()).containsEntry("degradationReasons", degradationReasons);
        assertBodyFreeMetadata(command);
    }

    private static void assertBodyFreeMetadata(AuditCommand command) {
        assertThat(command.metadata().keySet()).containsExactlyInAnyOrder(
                "queryCodePoints",
                "lexicalCount",
                "vectorCount",
                "fusedCount",
                "reauthorizedCount",
                "selectedCount",
                "degradationReasons",
                "durationMs");
        assertThat(command.metadata().toString())
                .doesNotContain("企业知识检索")
                .doesNotContain("候选")
                .doesNotContain("正文")
                .doesNotContain("秘密")
                .doesNotContain("evidence")
                .doesNotContain("principal");
    }

    private static TrustedRetrievalService trustedService(
            LexicalRetriever lexical,
            VectorRetriever vector,
            RetrievalAuthorizationPort authorization,
            Clock clock,
            AuditSink auditSink,
            java.util.concurrent.ExecutorService executor) {
        var policy = RetrievalPolicy.defaults();
        return new TrustedRetrievalService(
                (userId, at) -> Set.of(new PrincipalRef(PrincipalType.USER, userId)),
                new HybridRecallService(lexical, vector, executor, policy),
                new RerankingService(
                        (query, candidates) -> candidates.stream()
                                .map(candidate -> new RerankScore(
                                        candidate.candidate().candidateId(), candidate.score()))
                                .toList(),
                        executor,
                        policy),
                authorization,
                new EvidenceSelectionService(policy),
                at -> uuid(9300),
                policy,
                clock,
                auditSink);
    }

    private static TrustedRetrievalService serviceForPrincipalTest(
            PrincipalResolverPort resolver,
            AtomicInteger recallCalls,
            AtomicInteger rerankCalls,
            AtomicInteger authorizationCalls,
            AtomicInteger idCalls,
            java.util.concurrent.ExecutorService executor) {
        var candidate = candidate(30, 130, RecallSource.LEXICAL, 1, "候选正文");
        var recall = new HybridRecallService(
                (query, filter, limit) -> {
                    recallCalls.incrementAndGet();
                    return List.of(candidate);
                },
                (query, filter, limit) -> {
                    recallCalls.incrementAndGet();
                    return List.of();
                },
                executor,
                RetrievalPolicy.defaults());
        var reranking = new RerankingService(
                (query, candidates) -> {
                    rerankCalls.incrementAndGet();
                    return candidates.stream()
                            .map(value -> new RerankScore(
                                    value.candidate().candidateId(), value.score()))
                            .toList();
                },
                executor,
                RetrievalPolicy.defaults());
        return new TrustedRetrievalService(
                resolver,
                recall,
                reranking,
                (userId, requestedBySpace, checkedAt) -> {
                    authorizationCalls.incrementAndGet();
                    return flatten(requestedBySpace);
                },
                new EvidenceSelectionService(RetrievalPolicy.defaults()),
                at -> {
                    idCalls.incrementAndGet();
                    return uuid(9100 + idCalls.get());
                },
                RetrievalPolicy.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                command -> null);
    }

    private static RetrievalQuery query() {
        return new RetrievalQuery(
                REQUEST_ID,
                USER_ID,
                Set.of(SPACE_ID),
                "  企业知识检索  ",
                Map.of("year", "2026"),
                8);
    }

    private static Candidate candidate(
            int candidateSeed,
            int documentSeed,
            RecallSource source,
            int rank,
            String text) {
        return new Candidate(
                SPACE_ID,
                uuid(candidateSeed),
                uuid(documentSeed),
                uuid(documentSeed + 1000),
                "node-" + candidateSeed,
                "标题-" + candidateSeed,
                text,
                new PageLocator(candidateSeed, null),
                source,
                rank,
                1.0 / rank);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }

    private static Set<DocumentVersionRef> flatten(
            Map<UUID, Set<DocumentVersionRef>> requestedBySpace) {
        return requestedBySpace.values().stream()
                .flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static final class MutableAuthority {
        private final UUID expectedUserId;
        private final UUID expectedSpaceId;
        private final UUID roleId;
        private final Map<UUID, MutableDocument> documents = new HashMap<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<UUID> lastUserId = new AtomicReference<>();
        private final AtomicReference<UUID> lastSpaceId = new AtomicReference<>();
        private final AtomicReference<Instant> lastCheckedAt = new AtomicReference<>();
        private boolean roleMembershipActive = true;

        private MutableAuthority(
                UUID expectedUserId,
                UUID expectedSpaceId,
                UUID roleId,
                Instant recallStartedAt,
                List<Candidate> candidates,
                Candidate expired,
                Candidate membershipOnly) {
            this.expectedUserId = expectedUserId;
            this.expectedSpaceId = expectedSpaceId;
            this.roleId = roleId;
            for (var candidate : candidates) {
                var readers = new HashSet<PrincipalRef>();
                readers.add(candidate.equals(membershipOnly)
                        ? new PrincipalRef(PrincipalType.ROLE, roleId)
                        : new PrincipalRef(PrincipalType.USER, expectedUserId));
                documents.put(candidate.documentId(), new MutableDocument(
                        expectedSpaceId,
                        candidate.versionId(),
                        false,
                        candidate.equals(expired) ? recallStartedAt.plusSeconds(2) : null,
                        readers));
            }
        }

        private void changeAfterRecall(
                Candidate revoked,
                Candidate replaced,
                Candidate archived,
                Candidate membershipOnly) {
            documents.get(revoked.documentId()).readers.clear();
            documents.get(replaced.documentId()).currentVersionId = uuid(9998);
            documents.get(archived.documentId()).archived = true;
            if (!documents.containsKey(membershipOnly.documentId())) {
                throw new IllegalStateException("membership document is missing");
            }
            roleMembershipActive = false;
        }

        private Set<DocumentVersionRef> authorize(
                UUID userId,
                Map<UUID, Set<DocumentVersionRef>> requestedBySpace,
                Instant checkedAt) {
            calls.incrementAndGet();
            lastUserId.set(userId);
            lastSpaceId.set(requestedBySpace.keySet().stream().findFirst().orElse(null));
            lastCheckedAt.set(checkedAt);
            if (!expectedUserId.equals(userId)
                    || !requestedBySpace.keySet().equals(Set.of(expectedSpaceId))) {
                return Set.of();
            }
            var requested = flatten(requestedBySpace);
            var principals = new HashSet<PrincipalRef>();
            principals.add(new PrincipalRef(PrincipalType.USER, userId));
            if (roleMembershipActive) {
                principals.add(new PrincipalRef(PrincipalType.ROLE, roleId));
            }
            var allowed = new LinkedHashSet<DocumentVersionRef>();
            for (var reference : requested) {
                var document = documents.get(reference.documentId());
                if (document != null
                        && expectedSpaceId.equals(document.spaceId)
                        && reference.versionId().equals(document.currentVersionId)
                        && !document.archived
                        && (document.expiresAt == null || checkedAt.isBefore(document.expiresAt))
                        && document.readers.stream().anyMatch(principals::contains)) {
                    allowed.add(reference);
                }
            }
            return Set.copyOf(allowed);
        }

        private int authorizationCalls() {
            return calls.get();
        }

        private UUID lastUserId() {
            return lastUserId.get();
        }

        private UUID lastSpaceId() {
            return lastSpaceId.get();
        }

        private Instant lastCheckedAt() {
            return lastCheckedAt.get();
        }
    }

    private static final class MutableDocument {
        private final UUID spaceId;
        private UUID currentVersionId;
        private boolean archived;
        private final Instant expiresAt;
        private final Set<PrincipalRef> readers;

        private MutableDocument(
                UUID spaceId,
                UUID currentVersionId,
                boolean archived,
                Instant expiresAt,
                Set<PrincipalRef> readers) {
            this.spaceId = spaceId;
            this.currentVersionId = currentVersionId;
            this.archived = archived;
            this.expiresAt = expiresAt;
            this.readers = readers;
        }
    }

    private static final class AuthorizationHarness implements AutoCloseable {
        private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);
        private final AtomicInteger authorizationCalls = new AtomicInteger();
        private final AtomicInteger idCalls = new AtomicInteger();
        private final AtomicReference<Set<DocumentVersionRef>> requested = new AtomicReference<>();
        private final AtomicReference<List<UUID>> rerankedDocumentIds =
                new AtomicReference<>(List.of());
        private final AtomicReference<Instant> checkedAt = new AtomicReference<>();
        private final List<AuditCommand> auditCommands = new ArrayList<>();
        private final TrustedRetrievalService service;

        private AuthorizationHarness(
                List<Candidate> candidates,
                Function<Set<DocumentVersionRef>, Set<DocumentVersionRef>> authorizationResult,
                Clock clock) {
            var recall = new HybridRecallService(
                    (query, filter, limit) -> candidates,
                    (query, filter, limit) -> List.of(),
                    executor,
                    RetrievalPolicy.defaults());
            var reranking = new RerankingService(
                    (query, values) -> {
                        rerankedDocumentIds.set(values.stream()
                                .map(value -> value.candidate().documentId())
                                .toList());
                        return values.stream()
                                .map(value -> new RerankScore(
                                        value.candidate().candidateId(), value.score()))
                                .toList();
                    },
                    executor,
                    RetrievalPolicy.defaults());
            this.service = new TrustedRetrievalService(
                    (userId, at) -> Set.of(new PrincipalRef(PrincipalType.USER, userId)),
                    recall,
                    reranking,
                    (userId, requestedBySpace, authorizationTime) -> {
                        var requestedVersions = flatten(requestedBySpace);
                        authorizationCalls.incrementAndGet();
                        requested.set(requestedVersions);
                        checkedAt.set(authorizationTime);
                        return authorizationResult.apply(requestedVersions);
                    },
                    new EvidenceSelectionService(RetrievalPolicy.defaults()),
                    at -> {
                        int sequence = idCalls.incrementAndGet();
                        return uuid(9200 + sequence);
                    },
                    RetrievalPolicy.defaults(),
                    clock,
                    command -> {
                        auditCommands.add(command);
                        return null;
                    });
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static final class SequenceClock extends Clock {
        private final List<Instant> instants;
        private final AtomicInteger index = new AtomicInteger();

        private SequenceClock(List<Instant> instants) {
            this.instants = List.copyOf(instants);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("only UTC is supported in this test clock");
            }
            return this;
        }

        @Override
        public Instant instant() {
            int current = index.getAndIncrement();
            return instants.get(Math.min(current, instants.size() - 1));
        }
    }
}
