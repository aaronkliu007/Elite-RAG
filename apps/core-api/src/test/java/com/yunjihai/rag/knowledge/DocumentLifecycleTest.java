package com.yunjihai.rag.knowledge;

import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.access.AccessSnapshot;
import com.yunjihai.rag.access.EffectivePrincipalSet;
import com.yunjihai.rag.access.PrincipalMembershipSnapshot;
import com.yunjihai.rag.access.PublicationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentLifecycleTest {

    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");

    @Test
    @DisplayName("解析成功只更新解析状态，文档仍需编辑者显式提交评审")
    void parsingCompletionKeepsDraftUntilExplicitSubmission() {
        var repository = new InMemoryDocumentRepository();
        var reviews = new RecordingReviewStore();
        var service = service(repository, reviews);
        var document = service.createDraft(SPACE_ID, "投标方案", "blob-v1", ACTOR_ID, NOW);
        var versionId = document.latestVersion().id();

        document = service.completeParsing(document.id(), versionId, ACTOR_ID, NOW.plusSeconds(1));

        assertThat(document.version(versionId).parseStatus()).isEqualTo(ParseStatus.SUCCEEDED);
        assertThat(document.version(versionId).status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(reviews.values).isEmpty();
    }

    @Test
    @DisplayName("提交评审要求完整分类，并规范化去重排序标签")
    void requiresCompleteNormalizedMetadataBeforeReview() {
        var repository = new InMemoryDocumentRepository();
        var service = service(repository, new RecordingReviewStore());
        var document = service.createDraft(SPACE_ID, "需求规格", "blob-v1", ACTOR_ID, NOW);
        var versionId = document.latestVersion().id();
        service.completeParsing(document.id(), versionId, ACTOR_ID, NOW.plusSeconds(1));

        assertThatThrownBy(() -> service.submitForReview(
                document.id(), versionId, ACTOR_ID, NOW.plusSeconds(2)))
                .isInstanceOf(DocumentLifecycleException.class)
                .hasMessageContaining("元数据");

        var metadata = new DocumentMetadata("  制度规范  ", List.of("研发", " 安全 ", "研发"));
        assertThat(metadata.classification()).isEqualTo("制度规范");
        assertThat(metadata.tags()).containsExactly("安全", "研发");
        service.updateMetadata(document.id(), metadata, ACTOR_ID, NOW.plusSeconds(3));

        var submitted = service.submitForReview(
                document.id(), versionId, ACTOR_ID, NOW.plusSeconds(4));
        assertThat(submitted.version(versionId).status()).isEqualTo(DocumentStatus.IN_REVIEW);
    }

    @Test
    @DisplayName("分类和标签必须满足长度、数量与非空约束")
    void validatesClassificationAndTags() {
        assertThatThrownBy(() -> new DocumentMetadata(" ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentMetadata("分".repeat(101), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentMetadata("制度", List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentMetadata("制度", List.of("标".repeat(51))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentMetadata(
                "制度", java.util.stream.IntStream.range(0, 21).mapToObj(String::valueOf).toList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("驳回必须保留不可变意见并回到草稿，编辑者修改元数据不能覆盖审核记录")
    void rejectionPersistsImmutableReviewAndReturnsToDraft() {
        var repository = new InMemoryDocumentRepository();
        var reviews = new RecordingReviewStore();
        var service = service(repository, reviews);
        var document = service.createDraft(SPACE_ID, "安全制度", "blob-v1", ACTOR_ID, NOW);
        var documentId = document.id();
        var versionId = document.latestVersion().id();
        service.updateMetadata(documentId, new DocumentMetadata("制度", List.of("安全")),
                ACTOR_ID, NOW.plusSeconds(1));
        service.completeParsing(documentId, versionId, ACTOR_ID, NOW.plusSeconds(2));
        service.submitForReview(documentId, versionId, ACTOR_ID, NOW.plusSeconds(3));

        assertThatThrownBy(() -> service.reject(
                documentId, versionId, ACTOR_ID, " ", NOW.plusSeconds(4)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reject(
                documentId, versionId, ACTOR_ID, "意".repeat(1001), NOW.plusSeconds(4)))
                .isInstanceOf(IllegalArgumentException.class);

        document = service.reject(
                documentId, versionId, ACTOR_ID, "  请补充适用范围  ", NOW.plusSeconds(5));
        assertThat(document.version(versionId).status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(reviews.values).singleElement().satisfies(review -> {
            assertThat(review.decision()).isEqualTo(ReviewDecision.REJECTED);
            assertThat(review.comment()).isEqualTo("请补充适用范围");
        });

        var persistedReview = reviews.values.getFirst();
        service.updateMetadata(documentId, new DocumentMetadata("制度", List.of("安全", "正式")),
                ACTOR_ID, NOW.plusSeconds(6));
        assertThat(reviews.values).containsExactly(persistedReview);
    }

    @Test
    @DisplayName("文档只能按草稿、评审中、已发布、已归档的顺序流转")
    void followsGuardedLifecycleAndRequiresSuccessfulParsing() {
        var repository = new InMemoryDocumentRepository();
        var service = service(repository, new RecordingReviewStore());
        var document = service.createDraft(SPACE_ID, "投标方案", "blob-v1", ACTOR_ID, NOW);
        var documentId = document.id();
        var versionId = document.latestVersion().id();

        assertThatThrownBy(() -> service.submitForReview(
                documentId, versionId, ACTOR_ID, NOW.plusSeconds(1)))
                .isInstanceOf(DocumentLifecycleException.class)
                .hasMessageContaining("解析");

        service.updateMetadata(documentId, new DocumentMetadata("方案", List.of()),
                ACTOR_ID, NOW.plusSeconds(2));
        document = service.markParsingSucceeded(documentId, versionId, NOW.plusSeconds(2));
        document = service.submitForReview(documentId, versionId, ACTOR_ID, NOW.plusSeconds(3));
        assertThat(document.version(versionId).status()).isEqualTo(DocumentStatus.IN_REVIEW);
        document = service.publish(documentId, versionId, ACTOR_ID, NOW.plusSeconds(4));
        assertThat(document.currentPublishedVersionId()).contains(versionId);
        assertThat(document.version(versionId).status()).isEqualTo(DocumentStatus.PUBLISHED);

        document = service.archive(documentId, ACTOR_ID, NOW.plusSeconds(5));
        assertThat(document.version(versionId).status()).isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(document.archived()).isTrue();
    }

    @Test
    @DisplayName("新版本只能追加且发布时原子切换当前版本，不覆盖历史内容")
    void appendsImmutableVersionsAndSwitchesPublishedPointer() {
        var repository = new InMemoryDocumentRepository();
        var service = service(repository, new RecordingReviewStore());
        var document = service.createDraft(SPACE_ID, "需求规格", "blob-v1", ACTOR_ID, NOW);
        var firstVersionId = document.latestVersion().id();
        service.updateMetadata(document.id(), new DocumentMetadata("规格", List.of()),
                ACTOR_ID, NOW.plusSeconds(1));
        service.markParsingSucceeded(document.id(), firstVersionId, NOW.plusSeconds(1));
        service.submitForReview(document.id(), firstVersionId, ACTOR_ID, NOW.plusSeconds(2));
        service.publish(document.id(), firstVersionId, ACTOR_ID, NOW.plusSeconds(3));

        document = service.createNextDraft(document.id(), "blob-v2", ACTOR_ID, NOW.plusSeconds(4));
        var secondVersionId = document.latestVersion().id();
        service.markParsingSucceeded(document.id(), secondVersionId, NOW.plusSeconds(5));
        service.submitForReview(document.id(), secondVersionId, ACTOR_ID, NOW.plusSeconds(6));
        document = service.publish(document.id(), secondVersionId, ACTOR_ID, NOW.plusSeconds(7));

        assertThat(document.versions()).hasSize(2);
        assertThat(document.version(firstVersionId).sourceBlobId()).isEqualTo("blob-v1");
        assertThat(document.version(secondVersionId).sourceBlobId()).isEqualTo("blob-v2");
        assertThat(document.currentPublishedVersionId()).contains(secondVersionId);
        assertThat(document.version(firstVersionId).status()).isEqualTo(DocumentStatus.PUBLISHED);
    }

    @Test
    @DisplayName("没有空间写权限时不得创建或改变文档")
    void deniesEveryMutationBeforeRepositoryWrite() {
        var repository = new InMemoryDocumentRepository();
        var membership = new PrincipalMembershipSnapshot(Set.of(), Map.of(), Set.of(), Set.of());
        var access = new AccessDecisionService(new EffectivePrincipalSet(userId -> membership),
                (spaceId, documentId, refs, at) -> Optional.empty());
        var service = new DocumentService(
                repository, access, DocumentContentGateway.unavailable(),
                (userId, spaceId, at) -> List.of(), KnowledgeWriteAuthorization.denyAll());

        assertThatThrownBy(() -> service.createDraft(
                SPACE_ID, "越权文档", "blob", ACTOR_ID, NOW))
                .isInstanceOf(com.yunjihai.rag.access.AccessDeniedAsNotFoundException.class);
        assertThat(repository.values).isEmpty();
    }

    @Test
    @DisplayName("发布指针状态异常时不得读取解析内容或下载源文件")
    void deniesContentWhenPublishedPointerTargetsNonPublishedVersion() {
        var repository = new InMemoryDocumentRepository();
        var documentId = UUID.fromString("00000000-0000-0000-0000-000000000040");
        var versionId = UUID.fromString("00000000-0000-0000-0000-000000000041");
        var draft = new DocumentVersion(
                versionId, 1, "blob-v1", DocumentStatus.DRAFT, ParseStatus.SUCCEEDED,
                ACTOR_ID, NOW, ACTOR_ID, NOW);
        repository.insert(new DocumentAggregate(
                documentId, SPACE_ID, "坏数据", List.of(draft), versionId, false, 0));
        var reads = new int[1];
        var content = new DocumentContentGateway() {
            @Override
            public DocumentContent readParsed(UUID ignoredDocumentId, UUID ignoredVersionId) {
                reads[0]++;
                return new DocumentContent("text/markdown", "bad.md", new byte[]{1});
            }

            @Override
            public DocumentContent readSource(UUID ignoredDocumentId, UUID ignoredVersionId) {
                reads[0]++;
                return new DocumentContent("application/pdf", "bad.pdf", new byte[]{1});
            }
        };
        var membership = new PrincipalMembershipSnapshot(Set.of(), Map.of(), Set.of(), Set.of());
        var access = new AccessDecisionService(new EffectivePrincipalSet(userId -> membership),
                (spaceId, candidateDocumentId, refs, at) -> Optional.of(
                        new AccessSnapshot(true, true, true, PublicationState.PUBLISHED, null)));
        var service = new DocumentService(
                repository, access, content, (userId, spaceId, at) -> List.of(),
                KnowledgeWriteAuthorization.allowAll());

        assertThatThrownBy(() -> service.getAuthorizedParsedContent(ACTOR_ID, documentId, NOW))
                .isInstanceOf(com.yunjihai.rag.access.AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(() -> service.getAuthorizedSource(ACTOR_ID, documentId, NOW))
                .isInstanceOf(com.yunjihai.rag.access.AccessDeniedAsNotFoundException.class);
        assertThat(reads[0]).isZero();
    }

    private static DocumentService service(DocumentRepository repository, DocumentReviewStore reviews) {
        var membership = new PrincipalMembershipSnapshot(Set.of(), Map.of(), Set.of(), Set.of());
        var principals = new EffectivePrincipalSet(userId -> membership);
        var access = new AccessDecisionService(principals, (spaceId, documentId, refs, at) ->
                Optional.of(new AccessSnapshot(true, false, false, PublicationState.PUBLISHED, null)));
        return new DocumentService(repository, access, DocumentContentGateway.unavailable(),
                (userId, spaceId, at) -> List.of(), KnowledgeWriteAuthorization.allowAll(), reviews);
    }

    static final class RecordingReviewStore implements DocumentReviewStore {
        private final List<DocumentReview> values = new java.util.ArrayList<>();

        @Override
        public void append(DocumentReview review) {
            values.add(review);
        }

        @Override
        public List<DocumentReview> listByDocumentId(UUID documentId) {
            return values.stream().filter(review -> review.documentId().equals(documentId)).toList();
        }
    }

    static final class InMemoryDocumentRepository implements DocumentRepository {
        private final Map<UUID, DocumentAggregate> values = new HashMap<>();

        @Override
        public void insert(DocumentAggregate document) {
            if (values.putIfAbsent(document.id(), document) != null) {
                throw new IllegalStateException("duplicate document");
            }
        }

        @Override
        public Optional<DocumentAggregate> findById(UUID documentId) {
            return Optional.ofNullable(values.get(documentId));
        }

        @Override
        public Optional<DocumentVersionRef> findActiveVersionBySpaceAndSourceBlob(
                UUID spaceId, String sourceBlobId) {
            return values.values().stream()
                    .filter(document -> document.spaceId().equals(spaceId))
                    .filter(document -> !document.archived())
                    .flatMap(document -> document.versions().stream()
                            .filter(version -> version.sourceBlobId().equals(sourceBlobId))
                            .map(version -> new DocumentVersionRef(document.id(), version.id())))
                    .findFirst();
        }

        @Override
        public void replace(DocumentAggregate document, long expectedRevision) {
            values.compute(document.id(), (id, current) -> {
                if (current == null || current.revision() != expectedRevision) {
                    throw new ConcurrentDocumentUpdateException();
                }
                return document;
            });
        }
    }
}
