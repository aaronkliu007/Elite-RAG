package com.yunjihai.rag.infrastructure.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PrincipalType;
import com.yunjihai.rag.retrieval.domain.RecallSource;
import com.yunjihai.rag.retrieval.domain.RetrievalFilter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenSearchRecallQueryFactoryTest {
    private static final UUID FIRST_SPACE = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SECOND_SPACE = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void restrictsBothRecallPathsToExactDocumentVersion() {
        var factory = new OpenSearchRecallQueryFactory();
        var selected = new RetrievalFilter(Set.of(FIRST_SPACE), Set.of(new PrincipalRef(PrincipalType.USER, USER)),
                true, Instant.parse("2026-10-10T00:00:00Z"),
                Map.of("documentId", USER.toString(), "versionId", SECOND_SPACE.toString()));
        assertThat(factory.lexical("需求", selected, 10).toString())
                .contains("\"documentId\":\"" + USER + "\"", "\"versionId\":\"" + SECOND_SPACE + "\"");
        assertThat(factory.vector(List.of(0.1, 0.2), selected, 10).toString())
                .contains("\"documentId\":\"" + USER + "\"", "\"versionId\":\"" + SECOND_SPACE + "\"");
    }

    @Test
    void buildsTermsSpaceFilterForLexicalAndVectorRecall() {
        var factory = new OpenSearchRecallQueryFactory();
        var filter = filter();

        var lexical = factory.lexical("知识检索", filter, 10);
        var vector = factory.vector(List.of(0.1, 0.2), filter, 10);

        assertThat(lexical.toString()).contains("\"terms\":{\"spaceId\"");
        assertThat(vector.toString()).contains("\"terms\":{\"spaceId\"");
        assertThat(lexical.at("/_source").toString()).contains("spaceId");
        assertThat(lexical.toString()).contains(FIRST_SPACE.toString(), SECOND_SPACE.toString());
    }

    @Test
    void candidateMappingRequiresAndPreservesSpaceId() throws Exception {
        var json = new ObjectMapper();
        var mapper = new OpenSearchCandidateMapper();
        String hit = """
                {"hits":{"hits":[{"_score":0.8,"_source":{
                  "candidateId":"00000000-0000-0000-0000-000000000201",
                  "documentId":"00000000-0000-0000-0000-000000000202",
                  "versionId":"00000000-0000-0000-0000-000000000203",
                  "nodeId":"node-1","title":"标题","bodyText":"正文",
                  "locatorType":"MARKDOWN","headingPath":["章节"],"markdownNodeId":"node-1"
                }}]}}
                """;

        assertThatThrownBy(() -> mapper.map(json.readTree(hit), RecallSource.LEXICAL, 10))
                .isInstanceOf(IllegalStateException.class);

        var response = json.readTree(hit);
        ((com.fasterxml.jackson.databind.node.ObjectNode) response
                .at("/hits/hits/0/_source"))
                .put("spaceId", SECOND_SPACE.toString());

        assertThat(mapper.map(response, RecallSource.LEXICAL, 10).getFirst().spaceId())
                .isEqualTo(SECOND_SPACE);
    }

    private static RetrievalFilter filter() {
        return new RetrievalFilter(
                Set.of(FIRST_SPACE, SECOND_SPACE),
                Set.of(new PrincipalRef(PrincipalType.USER, USER)),
                true,
                Instant.parse("2026-10-10T00:00:00Z"),
                Map.of());
    }
}
