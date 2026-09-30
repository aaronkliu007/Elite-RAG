package com.yunjihai.rag.infrastructure.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Requests;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Pattern;

public final class OpenSearchIndexManager {
    public static final String READ_ALIAS = "rag-chunks-read";
    public static final String WRITE_ALIAS = "rag-chunks-write";
    private static final Pattern INDEX_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private final OpenSearchClient client;
    private final ObjectMapper json;

    public OpenSearchIndexManager(OpenSearchClient client) {
        this(client, new ObjectMapper());
    }

    OpenSearchIndexManager(OpenSearchClient client, ObjectMapper json) {
        this.client = Objects.requireNonNull(client);
        this.json = Objects.requireNonNull(json);
    }

    public void createGeneration(String indexName, OptionalInt vectorDimension) {
        validateIndex(indexName);
        Objects.requireNonNull(vectorDimension);
        if (vectorDimension.isPresent() && vectorDimension.getAsInt() < 1) {
            throw new IllegalArgumentException("vector dimension must be positive");
        }
        try (InputStream template = getClass().getResourceAsStream("/opensearch/chunks-v1.json")) {
            if (template == null) throw new IllegalStateException("OpenSearch mapping template is missing");
            ObjectNode mapping = (ObjectNode) json.readTree(template);
            if (vectorDimension.isPresent()) {
                ObjectNode properties = (ObjectNode) mapping.path("mappings").path("properties");
                ObjectNode embedding = properties.putObject("embedding");
                embedding.put("type", "knn_vector");
                embedding.put("dimension", vectorDimension.getAsInt());
                ObjectNode method = embedding.putObject("method");
                method.put("name", "hnsw");
                method.put("engine", "lucene");
                method.put("space_type", "cosinesimil");
            }
            execute("PUT", "/" + indexName, mapping.toString());
        } catch (IOException failure) {
            throw new IllegalStateException("OPENSEARCH_INDEX_CREATE_FAILED", failure);
        }
    }

    public void switchAliases(String oldIndex, String newIndex) {
        if (oldIndex != null) validateIndex(oldIndex);
        validateIndex(newIndex);
        var actions = json.createArrayNode();
        if (oldIndex != null) {
            for (String alias : new String[] {READ_ALIAS, WRITE_ALIAS}) {
                ObjectNode remove = actions.addObject().putObject("remove");
                remove.put("index", oldIndex);
                remove.put("alias", alias);
                remove.put("must_exist", false);
            }
        }
        for (String alias : new String[] {READ_ALIAS, WRITE_ALIAS}) {
            ObjectNode add = actions.addObject().putObject("add");
            add.put("index", newIndex);
            add.put("alias", alias);
        }
        ObjectNode body = json.createObjectNode().set("actions", actions);
        execute("POST", "/_aliases", body.toString());
    }

    public long countNodes(String indexName) {
        validateIndex(indexName);
        String response = execute("POST", "/" + indexName + "/_count",
                "{\"query\":{\"term\":{\"recordType\":\"NODE\"}}}");
        try {
            return json.readTree(response).path("count").asLong();
        } catch (IOException failure) {
            throw new IllegalStateException("OPENSEARCH_COUNT_RESPONSE_INVALID", failure);
        }
    }

    public void refresh(String indexName) {
        validateIndex(indexName);
        execute("POST", "/" + indexName + "/_refresh", null);
    }

    private String execute(String method, String endpoint, String body) {
        try {
            var builder = Requests.builder().method(method).endpoint(endpoint);
            if (body != null) builder.json(body);
            try (var response = client.generic().execute(builder.build())) {
                String responseBody = response.getBody().map(value -> value.bodyAsString()).orElse("");
                if (response.getStatus() < 200 || response.getStatus() >= 300) {
                    throw new IllegalStateException("OPENSEARCH_REQUEST_FAILED:" + response.getStatus());
                }
                return responseBody;
            }
        } catch (IOException failure) {
            throw new IllegalStateException("OPENSEARCH_REQUEST_FAILED", failure);
        }
    }

    static void validateIndex(String indexName) {
        if (indexName == null || !INDEX_NAME.matcher(indexName).matches()) {
            throw new IllegalArgumentException("invalid OpenSearch index name");
        }
    }
}
