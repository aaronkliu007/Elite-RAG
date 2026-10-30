package com.yunjihai.rag.infrastructure.model;

import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.qa.sanitization.SensitiveContentSanitizer;

import java.util.Objects;

final class ModelOutboundTextPolicy {
    private ModelOutboundTextPolicy() {
    }

    static String requireAlreadySanitized(SensitiveContentSanitizer sanitizer, String text) {
        try {
            var result = Objects.requireNonNull(sanitizer.sanitizeText(text));
            if (result.blocked() || !text.equals(result.value())) {
                throw new ModelGatewayException(ModelGatewayException.Kind.POLICY_VIOLATION);
            }
            return text;
        } catch (RuntimeException unsafe) {
            throw new ModelGatewayException(ModelGatewayException.Kind.POLICY_VIOLATION);
        }
    }
}
