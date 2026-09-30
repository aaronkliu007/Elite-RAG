package com.yunjihai.rag.infrastructure.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.yunjihai.rag.retrieval.domain.Candidate;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.NormalizedBoundingBox;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.retrieval.domain.RecallSource;
import com.yunjihai.rag.retrieval.domain.SlideLocator;
import com.yunjihai.rag.retrieval.domain.SourceLocator;
import com.yunjihai.rag.retrieval.domain.TableLocator;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class OpenSearchCandidateMapper {
    public List<Candidate> map(JsonNode response, RecallSource source, int limit) {
        try {
            var result = new ArrayList<Candidate>();
            for (JsonNode hit : response.path("hits").path("hits")) {
                if (result.size() == limit) break;
                JsonNode value = hit.path("_source");
                Double score = hit.path("_score").isNumber() ? hit.path("_score").asDouble() : null;
                result.add(new Candidate(
                        UUID.fromString(required(value, "candidateId")),
                        UUID.fromString(required(value, "documentId")),
                        UUID.fromString(required(value, "versionId")),
                        required(value, "nodeId"),
                        required(value, "title"),
                        required(value, "bodyText"),
                        locator(value), source, result.size() + 1, score));
            }
            return List.copyOf(result);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("OPENSEARCH_HIT_INVALID", failure);
        }
    }

    private static SourceLocator locator(JsonNode source) {
        return switch (required(source, "locatorType")) {
            case "PAGE" -> new PageLocator(source.path("pageNumber").asInt(), boundingBox(source));
            case "SLIDE" -> new SlideLocator(source.path("slideNumber").asInt());
            case "TABLE" -> new TableLocator(
                    required(source, "tableName"), source.path("startRow").asInt(),
                    source.path("endRow").asInt(), source.path("startColumn").asInt(),
                    source.path("endColumn").asInt());
            case "MARKDOWN" -> new MarkdownLocator(
                    strings(source.path("headingPath")), required(source, "markdownNodeId"));
            default -> throw new IllegalArgumentException("unknown locator type");
        };
    }

    private static NormalizedBoundingBox boundingBox(JsonNode source) {
        JsonNode box = source.path("boundingBox");
        if (box.isMissingNode() || box.isNull()) return null;
        return new NormalizedBoundingBox(
                box.path("left").asDouble(), box.path("top").asDouble(),
                box.path("right").asDouble(), box.path("bottom").asDouble());
    }

    private static List<String> strings(JsonNode array) {
        var values = new ArrayList<String>();
        array.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static String required(JsonNode source, String field) {
        String value = source.path(field).asText(null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing hit field");
        return value;
    }
}
