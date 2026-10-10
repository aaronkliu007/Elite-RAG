package com.yunjihai.rag.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.retrieval.domain.ScoredCandidate;
import com.yunjihai.rag.retrieval.selection.RerankScore;
import com.yunjihai.rag.retrieval.selection.RerankerPort;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class OpenAiRerankerAdapter implements RerankerPort {
    private static final int MAX_CANDIDATES = 100;
    private final ApprovedModelGatewayProperties properties;
    private final OpenAiCompatibleClient client;

    public OpenAiRerankerAdapter(
            ApprovedModelGatewayProperties properties, OpenAiCompatibleClient client) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    @Override
    public List<RerankScore> rerank(String queryText, List<ScoredCandidate> candidates) {
        if (queryText == null || queryText.isBlank() || candidates == null || candidates.isEmpty()
                || candidates.size() > MAX_CANDIDATES || candidates.stream().anyMatch(Objects::isNull)) {
            throw new ModelGatewayException(ModelGatewayException.Kind.POLICY_VIOLATION);
        }
        var requestedIds = new HashSet<UUID>();
        var request = JsonNodeFactory.instance.objectNode();
        request.put("model", properties.rerankerModel());
        request.put("query", queryText);
        var documents = request.putArray("documents");
        for (var scored : candidates) {
            var candidate = scored.candidate();
            if (!requestedIds.add(candidate.candidateId())) {
                throw new ModelGatewayException(ModelGatewayException.Kind.POLICY_VIOLATION);
            }
            var item = documents.addObject();
            item.put("candidate_id", candidate.candidateId().toString());
            item.put("text", candidate.text());
        }

        JsonNode results = client.post(ModelEndpoint.RERANK, request).body().path("results");
        if (!results.isArray() || results.size() != candidates.size()) throw invalidResponse();
        var returnedIds = new HashSet<UUID>();
        var scores = new java.util.ArrayList<RerankScore>(results.size());
        try {
            for (JsonNode item : results) {
                JsonNode idNode = item.get("candidate_id");
                JsonNode scoreNode = item.get("score");
                if (idNode == null || !idNode.isTextual() || scoreNode == null || !scoreNode.isNumber()) {
                    throw invalidResponse();
                }
                UUID id = UUID.fromString(idNode.textValue());
                double score = scoreNode.doubleValue();
                if (!requestedIds.contains(id) || !returnedIds.add(id) || !Double.isFinite(score)) {
                    throw invalidResponse();
                }
                scores.add(new RerankScore(id, score));
            }
            if (!returnedIds.equals(requestedIds)) throw invalidResponse();
            return List.copyOf(scores);
        } catch (ModelGatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidResponse();
        }
    }

    private static ModelGatewayException invalidResponse() {
        return new ModelGatewayException(ModelGatewayException.Kind.INVALID_RESPONSE);
    }
}
