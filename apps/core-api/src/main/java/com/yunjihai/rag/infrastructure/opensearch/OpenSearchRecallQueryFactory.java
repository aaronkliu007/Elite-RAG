package com.yunjihai.rag.infrastructure.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yunjihai.rag.retrieval.domain.RetrievalFilter;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class OpenSearchRecallQueryFactory {
    private static final Set<String> METADATA_KEYS = Set.of(
            "industry", "product", "solutionType", "year");
    private static final List<String> SOURCE_FIELDS = List.of(
            "spaceId", "candidateId", "documentId", "versionId", "nodeId", "title", "bodyText",
            "headingPath", "locatorType", "pageNumber", "boundingBox", "slideNumber",
            "tableName", "startRow", "endRow", "startColumn", "endColumn", "markdownNodeId");

    private final ObjectMapper json = new ObjectMapper();

    public ObjectNode lexical(String queryText, RetrievalFilter filter, int limit) {
        validateRequest(queryText, filter, limit);
        ObjectNode request = baseRequest(limit);
        ObjectNode bool = request.putObject("query").putObject("bool");
        ObjectNode multiMatch = bool.putArray("must").addObject().putObject("multi_match");
        multiMatch.put("query", queryText.strip());
        multiMatch.putArray("fields").add("title^2.0").add("headingPath^1.5").add("bodyText");
        bool.putArray("filter").add(securityFilter(filter));
        return request;
    }

    public ObjectNode vector(
            List<Double> vector, RetrievalFilter filter, int limit) {
        Objects.requireNonNull(vector);
        validateRequest("vector", filter, limit);
        ObjectNode request = baseRequest(limit);
        ObjectNode field = request.putObject("query").putObject("knn").putObject("embedding");
        field.putPOJO("vector", vector);
        field.put("k", limit);
        field.set("filter", securityFilter(filter));
        return request;
    }

    private ObjectNode baseRequest(int limit) {
        ObjectNode request = json.createObjectNode();
        request.put("size", limit);
        request.put("track_total_hits", false);
        request.putPOJO("_source", SOURCE_FIELDS);
        return request;
    }

    private ObjectNode securityFilter(RetrievalFilter filter) {
        var principalKeys = filter.effectivePrincipals().stream()
                .map(principal -> principal.type().name() + ":" + principal.id())
                .sorted().toList();
        if (principalKeys.isEmpty()) return json.createObjectNode().putObject("match_none");

        ObjectNode root = json.createObjectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode filters = bool.putArray("filter");
        filters.add(term("recordType", "NODE"));
        filters.add(term("currentPublished", true));
        filters.add(terms("spaceId", filter.spaceIds().stream()
                .map(Object::toString)
                .sorted()
                .toList()));
        filters.add(acl("spaceAcl", principalKeys, filter));

        ObjectNode authorization = json.createObjectNode();
        ObjectNode authBool = authorization.putObject("bool");
        ArrayNode alternatives = authBool.putArray("should");
        alternatives.add(term("documentRestricted", false));
        ObjectNode restricted = alternatives.addObject().putObject("bool");
        restricted.putArray("filter")
                .add(term("documentRestricted", true))
                .add(acl("documentAcl", principalKeys, filter));
        authBool.put("minimum_should_match", 1);
        filters.add(authorization);

        filter.metadataFilter().forEach((key, value) -> {
            if (!METADATA_KEYS.contains(key)) {
                throw new IllegalArgumentException("unsupported retrieval metadata key");
            }
            filters.add(term(key, value));
        });
        return root;
    }

    private ObjectNode acl(String path, List<String> principalKeys, RetrievalFilter filter) {
        ObjectNode nested = json.createObjectNode();
        ObjectNode nestedBody = nested.putObject("nested");
        nestedBody.put("path", path);
        ObjectNode grantBool = nestedBody.putObject("query").putObject("bool");
        ObjectNode terms = grantBool.putArray("filter").addObject().putObject("terms");
        terms.putPOJO(path + ".principalKey", principalKeys);
        ArrayNode expiry = grantBool.putArray("should");
        expiry.addObject().putObject("bool").putArray("must_not")
                .addObject().putObject("exists").put("field", path + ".expiresAt");
        expiry.addObject().putObject("range").putObject(path + ".expiresAt")
                .put("gt", filter.effectiveAt().toString());
        grantBool.put("minimum_should_match", 1);
        return nested;
    }

    private ObjectNode term(String field, String value) {
        ObjectNode query = json.createObjectNode();
        query.putObject("term").put(field, value);
        return query;
    }

    private ObjectNode term(String field, boolean value) {
        ObjectNode query = json.createObjectNode();
        query.putObject("term").put(field, value);
        return query;
    }

    private ObjectNode terms(String field, List<String> values) {
        ObjectNode query = json.createObjectNode();
        query.putObject("terms").putPOJO(field, values);
        return query;
    }

    private static void validateRequest(String queryText, RetrievalFilter filter, int limit) {
        Objects.requireNonNull(queryText);
        Objects.requireNonNull(filter);
        if (queryText.isBlank() || queryText.codePointCount(0, queryText.length()) > 2_000) {
            throw new IllegalArgumentException("retrieval query text is invalid");
        }
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("retrieval limit must be between 1 and 50");
        }
    }
}
