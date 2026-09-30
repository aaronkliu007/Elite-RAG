package com.yunjihai.rag.infrastructure.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Requests;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
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

    public Optional<String> aliasTarget(String alias) {
        if (!READ_ALIAS.equals(alias) && !WRITE_ALIAS.equals(alias)) {
            throw new IllegalArgumentException("unknown managed OpenSearch alias");
        }
        try {
            var request = Requests.builder().method("GET").endpoint("/_alias/" + alias).build();
            try (var response = client.generic().execute(request)) {
                if (response.getStatus() == 404) return Optional.empty();
                String responseBody = response.getBody().map(value -> value.bodyAsString()).orElse("{}");
                if (response.getStatus() < 200 || response.getStatus() >= 300) {
                    throw new IllegalStateException("OPENSEARCH_ALIAS_LOOKUP_FAILED:" + response.getStatus());
                }
                var targets = json.readTree(responseBody).properties().stream()
                        .map(java.util.Map.Entry::getKey).toList();
                if (targets.size() != 1) {
                    throw new IllegalStateException("OPENSEARCH_ALIAS_TARGET_AMBIGUOUS");
                }
                return Optional.of(targets.getFirst());
            }
        } catch (IOException failure) {
            throw new IllegalStateException("OPENSEARCH_ALIAS_LOOKUP_FAILED", failure);
        }
    }

    public long countNodes(String indexName) {
        return count(indexName, "{\"term\":{\"recordType\":\"NODE\"}}");
    }

    public long countTombstones(String indexName) {
        return count(indexName, "{\"term\":{\"recordType\":\"TOMBSTONE\"}}");
    }

    public long countNodesForSpace(String indexName, UUID spaceId) {
        Objects.requireNonNull(spaceId);
        return count(indexName, filtered(
                term("recordType", "NODE"), term("spaceId", spaceId.toString()),
                term("currentPublished", true)));
    }

    public long countNodesForDocumentVersion(
            String indexName, UUID documentId, UUID versionId) {
        Objects.requireNonNull(documentId);
        if (versionId == null) {
            return count(indexName, filtered(
                    term("recordType", "NODE"), term("documentId", documentId.toString())));
        }
        return count(indexName, filtered(
                term("recordType", "NODE"), term("documentId", documentId.toString()),
                term("versionId", versionId.toString()), term("currentPublished", true)));
    }

    public long countExpectedNode(
            String indexName, String nodeId, UUID spaceId, UUID documentId, UUID versionId) {
        Objects.requireNonNull(nodeId);
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(versionId);
        ObjectNode ids = json.createObjectNode();
        ids.putArray("values").add(nodeId);
        ObjectNode idsQuery = json.createObjectNode().set("ids", ids);
        return count(indexName, filtered(idsQuery,
                term("recordType", "NODE"), term("spaceId", spaceId.toString()),
                term("documentId", documentId.toString()), term("versionId", versionId.toString()),
                term("currentPublished", true)));
    }

    private long count(String indexName, String queryJson) {
        validateIndex(indexName);
        String response = execute("POST", "/" + indexName + "/_count",
                "{\"query\":" + queryJson + "}");
        try {
            return json.readTree(response).path("count").asLong();
        } catch (IOException failure) {
            throw new IllegalStateException("OPENSEARCH_COUNT_RESPONSE_INVALID", failure);
        }
    }

    private String filtered(ObjectNode... clauses) {
        ObjectNode query = json.createObjectNode();
        var filter = query.putObject("bool").putArray("filter");
        for (ObjectNode clause : clauses) filter.add(clause);
        return query.toString();
    }

    private ObjectNode term(String field, String value) {
        ObjectNode result = json.createObjectNode();
        result.putObject("term").put(field, value);
        return result;
    }

    private ObjectNode term(String field, boolean value) {
        ObjectNode result = json.createObjectNode();
        result.putObject("term").put(field, value);
        return result;
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
