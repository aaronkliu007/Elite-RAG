package com.yunjihai.rag.infrastructure.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.retrieval.domain.Candidate;
import com.yunjihai.rag.retrieval.domain.RecallSource;
import com.yunjihai.rag.retrieval.domain.RetrievalFilter;
import com.yunjihai.rag.retrieval.search.LexicalRetriever;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.Requests;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class OpenSearchLexicalRetriever implements LexicalRetriever {
    private final OpenSearchClient client;
    private final String index;
    private final OpenSearchRecallQueryFactory queries;
    private final OpenSearchCandidateMapper candidates;
    private final ObjectMapper json = new ObjectMapper();

    public OpenSearchLexicalRetriever(
            OpenSearchClient client,
            String index,
            OpenSearchRecallQueryFactory queries,
            OpenSearchCandidateMapper candidates) {
        this.client = Objects.requireNonNull(client);
        OpenSearchIndexManager.validateIndex(index);
        this.index = index;
        this.queries = Objects.requireNonNull(queries);
        this.candidates = Objects.requireNonNull(candidates);
    }

    @Override
    public List<Candidate> retrieve(String queryText, RetrievalFilter filter, int limit) {
        var requestBody = queries.lexical(queryText, filter, limit);
        try {
            var request = Requests.builder().method("POST").endpoint("/" + index + "/_search")
                    .json(requestBody.toString()).build();
            try (var response = client.generic().execute(request)) {
                String body = response.getBody().map(Body::bodyAsString).orElse("{}");
                if (response.getStatus() < 200 || response.getStatus() >= 300) {
                    throw new IllegalStateException("OPENSEARCH_LEXICAL_UNAVAILABLE");
                }
                return candidates.map(json.readTree(body), RecallSource.LEXICAL, limit);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("OPENSEARCH_LEXICAL_UNAVAILABLE", failure);
        }
    }
}
