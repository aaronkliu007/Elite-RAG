package com.yunjihai.rag.infrastructure.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.retrieval.domain.SlideLocator;
import com.yunjihai.rag.retrieval.domain.TableLocator;
import com.yunjihai.rag.retrieval.embedding.EmbeddingPort;
import com.yunjihai.rag.retrieval.embedding.EmbeddingVector;
import com.yunjihai.rag.retrieval.projection.ProjectionAclGrant;
import com.yunjihai.rag.retrieval.projection.ProjectionNode;
import com.yunjihai.rag.retrieval.projection.RetrievalProjectionSnapshot;
import com.yunjihai.rag.retrieval.projection.SearchProjectionWriter;
import com.yunjihai.rag.qa.sanitization.RegexSensitiveContentSanitizer;
import com.yunjihai.rag.qa.sanitization.SensitiveContentSanitizer;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.Requests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class OpenSearchProjectionWriter implements SearchProjectionWriter {
    private final OpenSearchClient client;
    private final ObjectMapper json;
    private final Consumer<String> targetResolved;
    private final SensitiveContentSanitizer sanitizer;
    private final EmbeddingPort embeddings;
    private final boolean vectorEnabled;
    private final int vectorDimension;

    public OpenSearchProjectionWriter(OpenSearchClient client) {
        this(client, new ObjectMapper(), ignored -> { },
                new RegexSensitiveContentSanitizer(Set.of()),
                texts -> { throw new IllegalStateException("EMBEDDING_DISABLED"); }, false, 0);
    }

    public OpenSearchProjectionWriter(
            OpenSearchClient client,
            SensitiveContentSanitizer sanitizer,
            EmbeddingPort embeddings,
            boolean vectorEnabled,
            int vectorDimension) {
        this(client, new ObjectMapper(), ignored -> { },
                sanitizer, embeddings, vectorEnabled, vectorDimension);
    }

    OpenSearchProjectionWriter(OpenSearchClient client, ObjectMapper json) {
        this(client, json, ignored -> { });
    }

    OpenSearchProjectionWriter(
            OpenSearchClient client, ObjectMapper json, Consumer<String> targetResolved) {
        this(client, json, targetResolved,
                new RegexSensitiveContentSanitizer(Set.of()),
                texts -> { throw new IllegalStateException("EMBEDDING_DISABLED"); }, false, 0);
    }

    OpenSearchProjectionWriter(
            OpenSearchClient client,
            ObjectMapper json,
            Consumer<String> targetResolved,
            SensitiveContentSanitizer sanitizer,
            EmbeddingPort embeddings,
            boolean vectorEnabled,
            int vectorDimension) {
        this.client = Objects.requireNonNull(client);
        this.json = Objects.requireNonNull(json);
        this.targetResolved = Objects.requireNonNull(targetResolved);
        this.sanitizer = Objects.requireNonNull(sanitizer);
        this.embeddings = Objects.requireNonNull(embeddings);
        if (vectorEnabled && vectorDimension < 1) {
            throw new IllegalArgumentException("vector dimension must be positive when enabled");
        }
        this.vectorEnabled = vectorEnabled;
        this.vectorDimension = vectorDimension;
    }

    @Override
    public void apply(String targetIndex, RetrievalProjectionSnapshot snapshot) {
        OpenSearchIndexManager.validateIndex(targetIndex);
        Objects.requireNonNull(snapshot);
        PreparedProjection prepared = snapshot.indexable() ? prepare(snapshot) : null;
        String concreteIndex = resolveConcreteTarget(targetIndex);
        targetResolved.accept(concreteIndex);
        if (!acceptTombstone(concreteIndex, snapshot)) return;
        if (snapshot.indexable()) {
            writeNodes(concreteIndex, snapshot, prepared);
            refresh(concreteIndex);
            deleteResidualNodes(concreteIndex, snapshot, snapshot.nodes().stream()
                    .map(ProjectionNode::indexDocumentId).collect(java.util.stream.Collectors.toSet()));
        } else {
            refresh(concreteIndex);
            deleteResidualNodes(concreteIndex, snapshot, Set.of());
        }
    }

    private PreparedProjection prepare(RetrievalProjectionSnapshot snapshot) {
        var title = sanitizer.sanitizeText(snapshot.title());
        if (title.blocked()) throw new IllegalStateException("PROJECTION_CONTENT_BLOCKED");

        var preparedNodes = new ArrayList<PreparedNode>(snapshot.nodes().size());
        var embeddingInputs = new ArrayList<String>(snapshot.nodes().size());
        for (ProjectionNode node : snapshot.nodes()) {
            var body = sanitizer.sanitizeText(node.bodyText());
            if (body.blocked()) throw new IllegalStateException("PROJECTION_CONTENT_BLOCKED");
            var headings = new ArrayList<String>(node.headingPath().size());
            for (String heading : node.headingPath()) {
                var sanitizedHeading = sanitizer.sanitizeText(heading);
                if (sanitizedHeading.blocked()) {
                    throw new IllegalStateException("PROJECTION_CONTENT_BLOCKED");
                }
                headings.add(sanitizedHeading.value());
            }
            embeddingInputs.add(body.value());
            preparedNodes.add(new PreparedNode(
                    node, body.value(), List.copyOf(headings), List.of()));
        }

        if (!vectorEnabled) return new PreparedProjection(title.value(), preparedNodes);

        List<EmbeddingVector> vectors = embeddings.embedAll(List.copyOf(embeddingInputs));
        if (vectors == null || vectors.size() != preparedNodes.size()
                || vectors.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("PROJECTION_EMBEDDING_BATCH_INVALID");
        }
        var vectorized = new ArrayList<PreparedNode>(preparedNodes.size());
        for (int index = 0; index < preparedNodes.size(); index++) {
            EmbeddingVector vector = vectors.get(index);
            if (vector.dimension() != vectorDimension) {
                throw new IllegalStateException("PROJECTION_EMBEDDING_DIMENSION_INVALID");
            }
            PreparedNode node = preparedNodes.get(index);
            vectorized.add(new PreparedNode(
                    node.source(), node.bodyText(), node.headingPath(), vector.values()));
        }
        return new PreparedProjection(title.value(), vectorized);
    }

    private String resolveConcreteTarget(String targetIndex) {
        if (!OpenSearchIndexManager.READ_ALIAS.equals(targetIndex)
                && !OpenSearchIndexManager.WRITE_ALIAS.equals(targetIndex)) {
            return targetIndex;
        }
        JsonNode aliases = executeJson("GET", "/_alias/" + targetIndex, null);
        var targets = aliases.properties().stream().map(java.util.Map.Entry::getKey).toList();
        if (targets.size() != 1) throw new IllegalStateException("OPENSEARCH_ALIAS_TARGET_AMBIGUOUS");
        String concrete = targets.getFirst();
        OpenSearchIndexManager.validateIndex(concrete);
        return concrete;
    }

    private boolean acceptTombstone(String index, RetrievalProjectionSnapshot snapshot) {
        ObjectNode tombstone = json.createObjectNode();
        tombstone.put("recordType", "TOMBSTONE");
        tombstone.put("spaceId", snapshot.spaceId().toString());
        tombstone.put("documentId", snapshot.documentId().toString());
        tombstone.put("documentRevision", snapshot.documentRevision());
        tombstone.put("projectionSequence", snapshot.projectionSequence());
        tombstone.put("projectedAt", Instant.now().toString());

        ObjectNode body = json.createObjectNode();
        body.put("scripted_upsert", true);
        ObjectNode script = body.putObject("script");
        script.put("lang", "painless");
        script.put("source", "if (ctx._source.projectionSequence != null && "
                + "ctx._source.projectionSequence > params.sequence) { ctx.op = 'none'; } "
                + "else { ctx._source.clear(); ctx._source.putAll(params.source); }");
        ObjectNode params = script.putObject("params");
        params.put("sequence", snapshot.projectionSequence());
        params.set("source", tombstone);
        body.set("upsert", tombstone);

        JsonNode response = executeJson("POST", "/" + index + "/_update/"
                + encodeId(tombstoneId(snapshot)), body);
        return !"noop".equals(response.path("result").asText());
    }

    private void writeNodes(
            String index, RetrievalProjectionSnapshot snapshot, PreparedProjection prepared) {
        StringBuilder ndjson = new StringBuilder();
        for (PreparedNode node : prepared.nodes()) {
            ObjectNode action = json.createObjectNode();
            ObjectNode updateAction = action.putObject("update");
            updateAction.put("_index", index);
            updateAction.put("_id", node.source().indexDocumentId());
            ndjson.append(action).append('\n');
            ObjectNode source = nodeDocument(snapshot, prepared.title(), node);
            ObjectNode update = json.createObjectNode();
            update.put("scripted_upsert", true);
            ObjectNode script = update.putObject("script");
            script.put("lang", "painless");
            script.put("source", "if (ctx._source.projectionSequence != null && "
                    + "ctx._source.projectionSequence > params.sequence) { ctx.op = 'none'; } "
                    + "else { ctx._source.clear(); ctx._source.putAll(params.source); }");
            script.putObject("params").put("sequence", snapshot.projectionSequence())
                    .set("source", source);
            update.set("upsert", source);
            ndjson.append(update).append('\n');
        }
        JsonNode response = executeRaw("POST", "/_bulk",
                Body.from(ndjson.toString().getBytes(StandardCharsets.UTF_8), "application/x-ndjson"));
        for (JsonNode item : response.path("items")) {
            JsonNode result = item.elements().hasNext() ? item.elements().next() : null;
            if (result == null || result.path("status").asInt(500) < 200
                    || result.path("status").asInt(500) >= 300 || result.has("error")) {
                throw new IllegalStateException("OPENSEARCH_BULK_ITEM_FAILED");
            }
        }
        if (response.path("errors").asBoolean() || response.path("items").size() != snapshot.nodes().size()) {
            throw new IllegalStateException("OPENSEARCH_BULK_ITEM_FAILED");
        }
    }

    private ObjectNode nodeDocument(
            RetrievalProjectionSnapshot snapshot, String title, PreparedNode prepared) {
        ProjectionNode node = prepared.source();
        ObjectNode document = json.createObjectNode();
        document.put("recordType", "NODE");
        document.put("spaceId", snapshot.spaceId().toString());
        document.put("documentId", snapshot.documentId().toString());
        document.put("versionId", snapshot.versionId().toString());
        document.put("candidateId", node.candidateId().toString());
        document.put("nodeId", node.nodeId());
        document.put("title", title);
        document.putPOJO("headingPath", prepared.headingPath());
        document.put("bodyText", prepared.bodyText());
        if (vectorEnabled) document.putPOJO("embedding", prepared.embedding());
        document.put("kind", node.kind());
        document.put("stableOrder", node.stableOrder());
        if (node.confidence() != null) document.put("confidence", node.confidence());
        addLocator(document, node);
        document.set("spaceAcl", acl(snapshot.spaceAclGrants()));
        document.set("documentAcl", acl(snapshot.documentAclGrants()));
        document.put("documentRestricted", snapshot.documentRestricted());
        document.put("currentPublished", true);
        snapshot.metadata().forEach(document::put);
        document.put("documentRevision", snapshot.documentRevision());
        document.put("projectionSequence", snapshot.projectionSequence());
        document.put("publishedAt", snapshot.publishedAt().toString());
        document.put("canonicalParsedAt", snapshot.canonicalParsedAt().toString());
        document.put("projectedAt", Instant.now().toString());
        return document;
    }

    private void addLocator(ObjectNode document, ProjectionNode node) {
        switch (node.locator()) {
            case PageLocator page -> {
                document.put("locatorType", "PAGE");
                document.put("pageNumber", page.pageNumber());
                if (page.boundingBox() != null) {
                    ObjectNode box = document.putObject("boundingBox");
                    box.put("left", page.boundingBox().left());
                    box.put("top", page.boundingBox().top());
                    box.put("right", page.boundingBox().right());
                    box.put("bottom", page.boundingBox().bottom());
                }
            }
            case SlideLocator slide -> {
                document.put("locatorType", "SLIDE");
                document.put("slideNumber", slide.slideNumber());
            }
            case TableLocator table -> {
                document.put("locatorType", "TABLE");
                document.put("tableName", table.tableName());
                document.put("startRow", table.startRow());
                document.put("endRow", table.endRow());
                document.put("startColumn", table.startColumn());
                document.put("endColumn", table.endColumn());
            }
            case MarkdownLocator markdown -> {
                document.put("locatorType", "MARKDOWN");
                if (markdown.nodeId() != null) document.put("markdownNodeId", markdown.nodeId());
            }
        }
    }

    private ArrayNode acl(Set<ProjectionAclGrant> grants) {
        ArrayNode result = json.createArrayNode();
        grants.stream().sorted(java.util.Comparator.comparing(ProjectionAclGrant::principalKey))
                .forEach(grant -> {
                    ObjectNode entry = result.addObject();
                    entry.put("principalKey", grant.principalKey());
                    if (grant.expiresAt() != null) entry.put("expiresAt", grant.expiresAt().toString());
                });
        return result;
    }

    private void deleteResidualNodes(
            String index, RetrievalProjectionSnapshot snapshot, Set<String> retainedIds) {
        ObjectNode body = json.createObjectNode();
        ObjectNode bool = body.putObject("query").putObject("bool");
        ArrayNode filters = bool.putArray("filter");
        filters.addObject().putObject("term").put("recordType", "NODE");
        filters.addObject().putObject("term").put("documentId", snapshot.documentId().toString());
        filters.addObject().putObject("range").putObject("projectionSequence")
                .put("lte", snapshot.projectionSequence());
        if (!retainedIds.isEmpty()) {
            ArrayNode values = bool.putArray("must_not").addObject()
                    .putObject("ids").putArray("values");
            retainedIds.stream().sorted().forEach(values::add);
        }
        JsonNode response = executeJson(
                "POST", "/" + index + "/_delete_by_query?conflicts=proceed", body);
        if (response.path("timed_out").asBoolean()
                || response.path("version_conflicts").asLong() != 0
                || !response.path("failures").isArray()
                || !response.path("failures").isEmpty()) {
            throw new IllegalStateException("OPENSEARCH_DELETE_INCOMPLETE");
        }
        refresh(index);
        ObjectNode count = json.createObjectNode().set("query", body.path("query"));
        JsonNode remaining = executeJson("POST", "/" + index + "/_count", count);
        if (remaining.path("count").asLong(-1) != 0) {
            throw new IllegalStateException("OPENSEARCH_DELETE_INCOMPLETE");
        }
    }

    private void refresh(String index) {
        execute("POST", "/" + index + "/_refresh",
                Requests.builder().method("POST").endpoint("/" + index + "/_refresh").build());
    }

    private JsonNode executeJson(String method, String endpoint, JsonNode body) {
        try {
            var builder = Requests.builder().method(method).endpoint(endpoint);
            if (body != null) builder.json(body.toString());
            return execute(method, endpoint, builder.build());
        } catch (RuntimeException failure) {
            throw failure;
        }
    }

    private JsonNode executeRaw(String method, String endpoint, Body body) {
        return execute(method, endpoint, Requests.builder().method(method).endpoint(endpoint)
                .body(body).build());
    }

    private JsonNode execute(String method, String endpoint,
            org.opensearch.client.opensearch.generic.Request request) {
        try (var response = client.generic().execute(request)) {
            String responseBody = response.getBody().map(Body::bodyAsString).orElse("{}");
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                throw new IllegalStateException("OPENSEARCH_REQUEST_FAILED:" + response.getStatus());
            }
            return json.readTree(responseBody);
        } catch (IOException failure) {
            throw new IllegalStateException("OPENSEARCH_REQUEST_FAILED", failure);
        }
    }

    private static String tombstoneId(RetrievalProjectionSnapshot snapshot) {
        return snapshot.documentId() + ":tombstone";
    }

    private static String encodeId(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record PreparedProjection(String title, List<PreparedNode> nodes) {
        private PreparedProjection {
            nodes = List.copyOf(nodes);
        }
    }

    private record PreparedNode(
            ProjectionNode source, String bodyText, List<String> headingPath, List<Double> embedding) {
        private PreparedNode {
            headingPath = List.copyOf(headingPath);
            embedding = List.copyOf(embedding);
        }
    }
}
