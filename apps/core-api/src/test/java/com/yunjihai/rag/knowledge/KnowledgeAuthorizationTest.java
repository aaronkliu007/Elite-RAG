package com.yunjihai.rag.knowledge;

import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.AccessSnapshot;
import com.yunjihai.rag.access.EffectivePrincipalSet;
import com.yunjihai.rag.access.PrincipalMembershipSnapshot;
import com.yunjihai.rag.access.PublicationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeAuthorizationTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");

    @Test
    @DisplayName("详情、解析内容和下载在未授权时统一表现为资源不存在")
    void hidesExistenceAcrossEveryDocumentReadSurface() {
        var fixture = fixture(false);

        assertThatThrownBy(() -> fixture.controller.getDocument(USER_ID, fixture.documentId, NOW))
                .isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(() -> fixture.controller.getParsedContent(USER_ID, fixture.documentId, NOW))
                .isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(() -> fixture.controller.downloadSource(USER_ID, fixture.documentId, NOW))
                .isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThat(fixture.contentGateway.readCount).isZero();
    }

    @Test
    @DisplayName("授权读取固定指向当前发布版本")
    void readsOnlyCurrentPublishedVersionAfterAuthorization() {
        var fixture = fixture(true);

        var view = fixture.controller.getDocument(USER_ID, fixture.documentId, NOW);
        var parsed = fixture.controller.getParsedContent(USER_ID, fixture.documentId, NOW);
        var source = fixture.controller.downloadSource(USER_ID, fixture.documentId, NOW);

        assertThat(view.currentPublishedVersionId()).isPresent();
        assertThat(view.versions())
                .singleElement()
                .extracting(DocumentView.VersionView::status)
                .isEqualTo(DocumentStatus.PUBLISHED);
        assertThat(Arrays.stream(DocumentView.VersionView.class.getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("sourceBlobId");
        assertThat(parsed.bytes()).containsExactly(1, 2, 3);
        assertThat(source.bytes()).containsExactly(4, 5, 6);
        assertThat(fixture.contentGateway.readCount).isEqualTo(2);
    }

    private static Fixture fixture(boolean granted) {
        var repository = new DocumentLifecycleTest.InMemoryDocumentRepository();
        var membership = new PrincipalMembershipSnapshot(Set.of(), Map.of(), Set.of(), Set.of());
        var principals = new EffectivePrincipalSet(userId -> membership);
        var access = new AccessDecisionService(principals, (spaceId, documentId, refs, at) ->
                Optional.of(new AccessSnapshot(granted, false, false, PublicationState.PUBLISHED, null)));
        var content = new RecordingContentGateway();
        var service = new DocumentService(
                repository, access, content, (userId, spaceId, at) -> List.of(),
                KnowledgeWriteAuthorization.allowAll());
        var document = service.createDraft(SPACE_ID, "已发布制度", "blob-v1", USER_ID, NOW.minusSeconds(10));
        var versionId = document.latestVersion().id();
        service.submitForReview(document.id(), versionId, USER_ID, NOW.minusSeconds(9));
        service.markParsingSucceeded(document.id(), versionId, NOW.minusSeconds(8));
        service.publish(document.id(), versionId, USER_ID, NOW.minusSeconds(7));
        service.createNextDraft(document.id(), "blob-v2-secret", USER_ID, NOW.minusSeconds(6));
        var spaces = new KnowledgeSpaceService(space -> { }, KnowledgeWriteAuthorization.allowAll());
        return new Fixture(new KnowledgeController(spaces, service), document.id(), content);
    }

    private record Fixture(KnowledgeController controller, UUID documentId, RecordingContentGateway contentGateway) {
    }

    private static final class RecordingContentGateway implements DocumentContentGateway {
        private int readCount;

        @Override
        public DocumentContent readParsed(UUID documentId, UUID versionId) {
            readCount++;
            return new DocumentContent("text/markdown", "parsed.md", new byte[]{1, 2, 3});
        }

        @Override
        public DocumentContent readSource(UUID documentId, UUID versionId) {
            readCount++;
            return new DocumentContent("application/pdf", "source.pdf", new byte[]{4, 5, 6});
        }
    }
}
