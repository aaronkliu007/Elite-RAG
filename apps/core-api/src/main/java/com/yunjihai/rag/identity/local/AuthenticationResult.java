package com.yunjihai.rag.identity.local;

import java.util.UUID;

public record AuthenticationResult(UUID userId, String username, String displayName, boolean passwordChangeRequired) {
    public AuthenticationResult(UUID userId, String username, String displayName) {
        this(userId, username, displayName, false);
    }
}
