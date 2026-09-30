package com.yunjihai.rag.infrastructure.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.retrieval.domain.Candidate;
import com.yunjihai.rag.retrieval.domain.RecallSource;
import com.yunjihai.rag.retrieval.domain.RetrievalFilter;
import com.yunjihai.rag.retrieval.embedding.EmbeddingPort;
import com.yunjihai.rag.retrieval.embedding.EmbeddingUnavailableException;
import com.yunjihai.rag.retrieval.embedding.EmbeddingVector;
import com.yunjihai.rag.retrieval.search.VectorRetriever;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.Requests;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class OpenSearchVectorRetriever implements VectorRetriever {
    private final OpenSearchClient client;
    private final String index;
    private final EmbeddingPort embeddings;
    private final int expectedDimension;
    private final OpenSearchRecallQueryFactory queries;
    private final OpenSearchCandidateMapper candidates;
    private final ObjectMapper json = new ObjectMapper();

    public OpenSearchVectorRetriever(
            OpenSearchClient client,
            String index,
            EmbeddingPort embeddings,
            int expectedDimension,
            OpenSearchRecallQueryFactory queries,
            OpenSearchCandidateMapper candidates) {
        this.client = Objects.requireNonNull(client);
        OpenSearchIndexManager.validateIndex(index);
        this.index = index;
        this.embeddings = Objects.requireNonNull(embeddings);
        if (expectedDimension < 1) throw new IllegalArgumentException("embedding dimension must be positive");
        this.expectedDimension = expectedDimension;
        this.queries = Objects.requireNonNull(queries);
        this.candidates = Objects.requireNonNull(candidates);
    }

    @Override
    public List<Candidate> retrieve(String queryText, RetrievalFilter filter, int limit) {
        if (queryText == null || queryText.isBlank()
                || queryText.codePointCount(0, queryText.length()) > 2_000) {
            throw new IllegalArgumentException("retrieval query text is invalid");
        }
        Objects.requireNonNull(filter);
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("retrieval limit must be between 1 and 50");
        }
        EmbeddingVector vector = embed(queryText);
        if (vector.dimension() != expectedDimension) {
            throw new EmbeddingUnavailableException("EMBEDDING_DIMENSION_MISMATCH");
        }
        var requestBody = queries.vector(vector.values(), filter, limit);
        try {
            var request = Requests.builder().method("POST").endpoint("/" + index + "/_search")
                    .json(requestBody.toString()).build();
            try (var response = client.generic().execute(request)) {
                String body = response.getBody().map(Body::bodyAsString).orElse("{}");
                if (response.getStatus() < 200 || response.getStatus() >= 300) {
                    throw new IllegalStateException("OPENSEARCH_VECTOR_UNAVAILABLE");
                }
                return candidates.map(json.readTree(body), RecallSource.VECTOR, limit);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("OPENSEARCH_VECTOR_UNAVAILABLE", failure);
        }
    }

    private EmbeddingVector embed(String queryText) {
        try {
            EmbeddingVector vector = embeddings.embed(queryText);
            if (vector == null) throw new EmbeddingUnavailableException("EMBEDDING_EMPTY_RESPONSE");
            return vector;
        } catch (EmbeddingUnavailableException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new EmbeddingUnavailableException("EMBEDDING_REQUEST_FAILED", failure);
        }
    }
}
