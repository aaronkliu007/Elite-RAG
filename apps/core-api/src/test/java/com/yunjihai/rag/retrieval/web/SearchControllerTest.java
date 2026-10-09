package com.yunjihai.rag.retrieval.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.api.ApiExceptionHandler;
import com.yunjihai.rag.api.RequestTraceFilter;
import com.yunjihai.rag.knowledge.KnowledgeSpaceReadAuthorization;
import com.yunjihai.rag.retrieval.RetrievalAuthorizationUnavailableException;
import com.yunjihai.rag.retrieval.RetrievalService;
import com.yunjihai.rag.retrieval.domain.DegradationReason;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.EvidencePack;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.NormalizedBoundingBox;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import com.yunjihai.rag.retrieval.domain.RetrievalStats;
import com.yunjihai.rag.retrieval.domain.SlideLocator;
import com.yunjihai.rag.retrieval.domain.TableLocator;
import com.yunjihai.rag.retrieval.search.RetrievalUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-09T06:00:00Z");
    private static final UUID USER = id(1);
    private static final UUID SPACE = id(2);

    @Test
    void rejectsInvalidQueriesBeforeCallingRetrieval() {
        var calls = new AtomicInteger();
        var controller = controller(query -> {
            calls.incrementAndGet();
            return emptyPack(query.requestId());
        }, (userId, spaceId, at) -> { });
        var authentication = authentication();

        assertThatThrownBy(() -> controller.search(authentication,
                new SearchRequest(SPACE, "　 ", Map.of(), 8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.search(authentication,
                new SearchRequest(SPACE, "𠮷".repeat(2001), Map.of(), 8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.search(authentication,
                new SearchRequest(SPACE, "部署", Map.of(), 7)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.search(authentication,
                new SearchRequest(SPACE, "部署", Map.of(), 16)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.search(authentication,
                new SearchRequest(null, "部署", Map.of(), 8)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(calls).hasValue(0);
    }

    @Test
    void deniesUnauthorizedSpaceWithoutSearching() {
        var calls = new AtomicInteger();
        var controller = controller(query -> {
            calls.incrementAndGet();
            return emptyPack(query.requestId());
        }, (userId, spaceId, at) -> { throw new AccessDeniedAsNotFoundException(); });

        assertThatThrownBy(() -> controller.search(authentication(),
                new SearchRequest(SPACE, "部署方案", Map.of(), 8)))
                .isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void returnsPermissionFilteredEvidenceWithExplicitLocatorsAndDegradation() {
        var captured = new AtomicReference<RetrievalQuery>();
        var controller = controller(query -> {
            captured.set(query);
            return pack(query.requestId());
        }, (userId, spaceId, at) -> { });

        var response = controller.search(authentication(),
                new SearchRequest(SPACE, "  灰度　部署  ", Map.of("classification", "方案"), 8));

        assertThat(captured.get().requestId().version()).isEqualTo(7);
        assertThat(captured.get().userId()).isEqualTo(USER);
        assertThat(captured.get().spaceId()).isEqualTo(SPACE);
        assertThat(captured.get().queryText()).isEqualTo("灰度 部署");
        assertThat(response.requestId()).isEqualTo(captured.get().requestId());
        assertThat(response.degraded()).isTrue();
        assertThat(response.degradationReasons()).containsExactly(DegradationReason.VECTOR_UNAVAILABLE);
        assertThat(response.evidence()).extracting(item -> item.locator().type())
                .containsExactly("PAGE", "SLIDE", "TABLE", "MARKDOWN");
        assertThat(response.evidence().getFirst().locator())
                .isInstanceOf(SearchResponse.PageLocatorView.class);
        assertThat(response.evidence().get(2).locator())
                .isInstanceOf(SearchResponse.TableLocatorView.class);
        assertThat(response.evidence().getLast().locator())
                .isInstanceOf(SearchResponse.MarkdownLocatorView.class);
        assertThat(SearchResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("vector", "acl", "principals");
        assertThat(SearchResponse.EvidenceView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("vector", "acl", "principals", "sourceBlobId");
    }

    @Test
    void mapsRecallAndAuthorizationOutagesToServiceUnavailable() throws Exception {
        var failure = new AtomicReference<RuntimeException>(
                new RetrievalUnavailableException("opensearch internal endpoint"));
        var controller = controller(query -> { throw failure.get(); }, (userId, spaceId, at) -> { });
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestTraceFilter())
                .build();
        String body = """
                {"spaceId":"%s","queryText":"部署方案","metadataFilter":{},"requestedEvidenceCount":8}
                """.formatted(SPACE);

        mvc.perform(post("/api/v1/search")
                        .principal(authentication())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RETRIEVAL_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("检索服务暂时不可用"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("opensearch internal endpoint"));

        failure.set(new RetrievalAuthorizationUnavailableException("postgres internal endpoint"));
        mvc.perform(post("/api/v1/search")
                        .principal(authentication())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RETRIEVAL_UNAVAILABLE"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("postgres internal endpoint"));
    }

    private static SearchController controller(
            RetrievalService retrieval,
            KnowledgeSpaceReadAuthorization spaces) {
        return new SearchController(retrieval, spaces, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken(USER.toString(), "n/a", List.of());
    }

    private static EvidencePack emptyPack(UUID requestId) {
        return new EvidencePack(requestId, List.of(), false, Set.of(),
                new RetrievalStats(0, 0, 0, 0, 0), NOW);
    }

    private static EvidencePack pack(UUID requestId) {
        var evidence = List.of(
                evidence(10, new PageLocator(3, new NormalizedBoundingBox(0.1, 0.2, 0.8, 0.9))),
                evidence(11, new SlideLocator(5)),
                evidence(12, new TableLocator("Sheet1", 2, 4, 1, 3)),
                evidence(13, new MarkdownLocator(List.of("部署", "灰度"), "node-13")));
        return new EvidencePack(requestId, evidence, true,
                Set.of(DegradationReason.VECTOR_UNAVAILABLE),
                new RetrievalStats(4, 0, 4, 4, 4), NOW);
    }

    private static Evidence evidence(int suffix, com.yunjihai.rag.retrieval.domain.SourceLocator locator) {
        return new Evidence(id(100 + suffix), id(200 + suffix), id(300 + suffix),
                "node-" + suffix, "部署标题", "部署摘要", 0.8, locator);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix));
    }
}
