package com.yunjihai.rag.retrieval.domain;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class DomainValidation {

    private DomainValidation() {
    }

    static String normalizeQuery(String value) {
        Objects.requireNonNull(value, "query text must not be null");
        var normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        var result = new StringBuilder(normalized.length());
        var previousWasWhitespace = true;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (!previousWasWhitespace) {
                    result.append(' ');
                    previousWasWhitespace = true;
                }
            } else {
                result.appendCodePoint(codePoint);
                previousWasWhitespace = false;
            }
        }
        int length = result.length();
        if (length > 0 && result.charAt(length - 1) == ' ') {
            result.setLength(length - 1);
        }
        return result.toString();
    }

    static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    static Map<String, String> immutableMetadata(Map<String, String> metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (metadata.size() > 20) {
            throw new IllegalArgumentException("metadata contains too many entries");
        }
        var copy = new LinkedHashMap<String, String>();
        metadata.forEach((key, value) -> {
            if (key == null || key.isBlank() || codePointLength(key) > 64) {
                throw new IllegalArgumentException("metadata key is invalid");
            }
            if (value == null || value.isBlank() || codePointLength(value) > 256) {
                throw new IllegalArgumentException("metadata value is invalid");
            }
            copy.put(key, value);
        });
        return Map.copyOf(copy);
    }
}
