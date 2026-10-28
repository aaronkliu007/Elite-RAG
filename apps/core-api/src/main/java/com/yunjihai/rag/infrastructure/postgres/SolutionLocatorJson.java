package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.retrieval.domain.*;
import java.util.*;

public final class SolutionLocatorJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SolutionLocatorJson() { }

    public static String write(SourceLocator locator) {
        Objects.requireNonNull(locator);
        Map<String, Object> fields = new LinkedHashMap<>();
        switch (locator) {
            case PageLocator page -> {
                fields.put("type", "PAGE");
                fields.put("pageNumber", page.pageNumber());
                if (page.boundingBox() != null) {
                    var box = page.boundingBox();
                    fields.put("box", Map.of("left", box.left(), "top", box.top(),
                            "right", box.right(), "bottom", box.bottom()));
                }
            }
            case SlideLocator slide -> {
                fields.put("type", "SLIDE");
                fields.put("slideNumber", slide.slideNumber());
            }
            case TableLocator table -> {
                fields.put("type", "TABLE");
                fields.put("tableName", table.tableName());
                fields.put("startRow", table.startRow());
                fields.put("endRow", table.endRow());
                fields.put("startColumn", table.startColumn());
                fields.put("endColumn", table.endColumn());
            }
            case MarkdownLocator markdown -> {
                fields.put("type", "MARKDOWN");
                fields.put("headingPath", markdown.headingPath());
                fields.put("nodeId", markdown.nodeId());
            }
        }
        try {
            return JSON.writeValueAsString(fields);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("invalid locator", failure);
        }
    }

    public static SourceLocator read(String value) {
        try {
            JsonNode node = JSON.readTree(value);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("invalid locator");
            return switch (requiredText(node, "type")) {
                case "PAGE" -> {
                    var box = node.path("box");
                    yield new PageLocator(requiredInt(node, "pageNumber"), box.isMissingNode() ? null
                            : new NormalizedBoundingBox(requiredDouble(box, "left"), requiredDouble(box, "top"),
                                    requiredDouble(box, "right"), requiredDouble(box, "bottom")));
                }
                case "SLIDE" -> new SlideLocator(requiredInt(node, "slideNumber"));
                case "TABLE" -> new TableLocator(requiredText(node, "tableName"), requiredInt(node, "startRow"),
                        requiredInt(node, "endRow"), requiredInt(node, "startColumn"), requiredInt(node, "endColumn"));
                case "MARKDOWN" -> {
                    var headings = node.path("headingPath");
                    if (!headings.isArray()) throw new IllegalArgumentException("invalid headings");
                    List<String> values = new ArrayList<>();
                    headings.forEach(item -> {
                        if (!item.isTextual()) throw new IllegalArgumentException("invalid heading");
                        values.add(item.asText());
                    });
                    var id = node.path("nodeId");
                    yield new MarkdownLocator(values, id.isNull() || id.isMissingNode() ? null : id.asText());
                }
                default -> throw new IllegalArgumentException("unsupported locator type");
            };
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("invalid locator", failure);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("invalid locator field");
        return value.asText();
    }

    private static int requiredInt(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("invalid locator field");
        return value.intValue();
    }

    private static double requiredDouble(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isNumber()) throw new IllegalArgumentException("invalid locator field");
        return value.doubleValue();
    }
}
