package com.yunjihai.rag.qa.domain;

import java.text.Normalizer;
import java.util.Objects;

public record QaQuestion(String value) {
    private static final int MAX_CODE_POINTS = 2_000;

    public QaQuestion {
        Objects.requireNonNull(value, "question must not be null");
        if (value.isBlank() || value.codePointCount(0, value.length()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("question length is invalid");
        }
    }

    public static QaQuestion normalize(String raw) {
        Objects.requireNonNull(raw, "question must not be null");
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        var result = new StringBuilder(normalized.length());
        boolean previousWhitespace = true;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (!previousWhitespace) result.append(' ');
                previousWhitespace = true;
            } else {
                result.appendCodePoint(codePoint);
                previousWhitespace = false;
            }
        }
        if (!result.isEmpty() && result.charAt(result.length() - 1) == ' ') {
            result.setLength(result.length() - 1);
        }
        return new QaQuestion(result.toString());
    }

    public int codePointLength() {
        return value.codePointCount(0, value.length());
    }
}
