package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.retrieval.domain.*;

import java.util.List;

final class QaLocatorJson {
    private QaLocatorJson() { }

    static String type(SourceLocator locator) {
        return switch (locator) {
            case PageLocator ignored -> "PAGE";
            case SlideLocator ignored -> "SLIDE";
            case TableLocator ignored -> "TABLE";
            case MarkdownLocator ignored -> "MARKDOWN";
            case SectionLocator ignored -> "SECTION";
        };
    }

    static String write(ObjectMapper json, SourceLocator locator) {
        try {
            return json.writeValueAsString(locator);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("QA_LOCATOR_SERIALIZATION_FAILED", failure);
        }
    }

    static SourceLocator read(ObjectMapper json, String type, String value) {
        try {
            JsonNode node = json.readTree(value);
            return switch (type) {
                case "PAGE" -> new PageLocator(node.path("pageNumber").asInt(), box(node.get("boundingBox")));
                case "SLIDE" -> new SlideLocator(node.path("slideNumber").asInt());
                case "TABLE" -> new TableLocator(
                        node.path("tableName").asText(), node.path("startRow").asInt(),
                        node.path("endRow").asInt(), node.path("startColumn").asInt(),
                        node.path("endColumn").asInt());
                case "SECTION" -> new SectionLocator(
                        json.convertValue(node.path("headingPath"),
                                json.getTypeFactory().constructCollectionType(List.class, String.class)),
                        node.path("nodeId").isNull() ? null : node.path("nodeId").asText());
                case "MARKDOWN" -> new MarkdownLocator(
                        json.convertValue(node.path("headingPath"),
                                json.getTypeFactory().constructCollectionType(List.class, String.class)),
                        node.path("nodeId").isNull() ? null : node.path("nodeId").asText());
                default -> throw new IllegalStateException("UNKNOWN_QA_LOCATOR_TYPE");
            };
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalStateException("QA_LOCATOR_DESERIALIZATION_FAILED", failure);
        }
    }

    private static NormalizedBoundingBox box(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return new NormalizedBoundingBox(
                node.path("left").asDouble(), node.path("top").asDouble(),
                node.path("right").asDouble(), node.path("bottom").asDouble());
    }
}
