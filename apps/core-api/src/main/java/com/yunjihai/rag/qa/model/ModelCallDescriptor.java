package com.yunjihai.rag.qa.model;

import java.util.Objects;
import java.util.regex.Pattern;

public record ModelCallDescriptor(
        String provider,
        String endpointIdentifier,
        String model,
        String configVersion) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    public ModelCallDescriptor {
        requireIdentifier(provider, "provider");
        requireIdentifier(endpointIdentifier, "endpointIdentifier");
        requireText(model, "model", 200);
        requireIdentifier(configVersion, "configVersion");
    }

    private static void requireIdentifier(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireText(String value, String field, int maxCodePoints) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank() || value.codePointCount(0, value.length()) > maxCodePoints) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
