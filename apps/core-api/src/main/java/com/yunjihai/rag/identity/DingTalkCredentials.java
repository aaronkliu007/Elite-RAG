package com.yunjihai.rag.identity;

import java.util.Objects;

public record DingTalkCredentials(String clientId, String clientSecret) {

    public DingTalkCredentials {
        requireText(clientId, "clientId");
        requireText(clientSecret, "clientSecret");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    @Override
    public String toString() {
        return "DingTalkCredentials[clientId=" + Objects.toString(clientId) + ", clientSecret=<redacted>]";
    }
}
