package com.yunjihai.rag.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.retrieval.embedding.EmbeddingPort;
import com.yunjihai.rag.retrieval.embedding.EmbeddingVector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class OpenAiEmbeddingAdapter implements EmbeddingPort {
    private static final int MAX_BATCH_SIZE = 128;
    private final ApprovedModelGatewayProperties properties;
    private final OpenAiCompatibleClient client;

    public OpenAiEmbeddingAdapter(
            ApprovedModelGatewayProperties properties, OpenAiCompatibleClient client) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    @Override
    public List<EmbeddingVector> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty() || texts.size() > MAX_BATCH_SIZE
                || texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new ModelGatewayException(ModelGatewayException.Kind.POLICY_VIOLATION);
        }
        var request = JsonNodeFactory.instance.objectNode();
        request.put("model", properties.embeddingModel());
        var input = request.putArray("input");
        texts.forEach(input::add);

        JsonNode data = client.post(ModelEndpoint.EMBEDDINGS, request).body().path("data");
        if (!data.isArray() || data.size() != texts.size()) throw invalidResponse();

        var byIndex = new ArrayList<EmbeddingVector>(java.util.Collections.nCopies(texts.size(), null));
        var seen = new HashSet<Integer>();
        int dimension = -1;
        try {
            for (JsonNode item : data) {
                JsonNode indexNode = item.get("index");
                JsonNode vectorNode = item.get("embedding");
                if (indexNode == null || !indexNode.canConvertToInt() || vectorNode == null || !vectorNode.isArray()) {
                    throw invalidResponse();
                }
                int index = indexNode.intValue();
                if (index < 0 || index >= texts.size() || !seen.add(index)) throw invalidResponse();
                var values = new ArrayList<Double>(vectorNode.size());
                for (JsonNode value : vectorNode) {
                    if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw invalidResponse();
                    values.add(value.doubleValue());
                }
                var vector = new EmbeddingVector(values);
                if (dimension < 0) dimension = vector.dimension();
                if (vector.dimension() != dimension) throw invalidResponse();
                byIndex.set(index, vector);
            }
            if (byIndex.stream().anyMatch(Objects::isNull)) throw invalidResponse();
            return List.copyOf(byIndex);
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
