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
    @DisplayName("文档只能按草稿、评审中、已发布、已归档的顺序流转")
    void followsGuardedLifecycleAndRequiresSuccessfulParsing() {
        var repository = new InMemoryDocumentRepository();
        var service = service(repository);
        var document = service.createDraft(SPACE_ID, "投标方案", "blob-v1", ACTOR_ID, NOW);
        var documentId = document.id();
        var versionId = document.latestVersion().id();

        document = service.submitForReview(documentId, versionId, ACTOR_ID, NOW.plusSeconds(1));
        assertThat(document.version(versionId).status()).isEqualTo(DocumentStatus.IN_REVIEW);
        assertThatThrownBy(() -> service.publish(
                documentId, versionId, ACTOR_ID, NOW.plusSeconds(2)))
                .isInstanceOf(DocumentLifecycleException.class)
                .hasMessageContaining("解析");

        document = service.markParsingSucceeded(documentId, versionId, NOW.plusSeconds(3));
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
        var service = service(repository);
        var document = service.createDraft(SPACE_ID, "需求规格", "blob-v1", ACTOR_ID, NOW);
        var firstVersionId = document.latestVersion().id();
        service.submitForReview(document.id(), firstVersionId, ACTOR_ID, NOW.plusSeconds(1));
        service.markParsingSucceeded(document.id(), firstVersionId, NOW.plusSeconds(2));
        service.publish(document.id(), firstVersionId, ACTOR_ID, NOW.plusSeconds(3));

        document = service.createNextDraft(document.id(), "blob-v2", ACTOR_ID, NOW.plusSeconds(4));
        var secondVersionId = document.latestVersion().id();
        service.submitForReview(document.id(), secondVersionId, ACTOR_ID, NOW.plusSeconds(5));
        service.markParsingSucceeded(document.id(), secondVersionId, NOW.plusSeconds(6));
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

    private static DocumentService service(DocumentRepository repository) {
        var membership = new PrincipalMembershipSnapshot(Set.of(), Map.of(), Set.of(), Set.of());
        var principals = new EffectivePrincipalSet(userId -> membership);
        var access = new AccessDecisionService(principals, (spaceId, documentId, refs, at) ->
                Optional.of(new AccessSnapshot(true, false, false, PublicationState.PUBLISHED, null)));
        return new DocumentService(repository, access, DocumentContentGateway.unavailable(),
                (userId, spaceId, at) -> List.of(), KnowledgeWriteAuthorization.allowAll());
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
