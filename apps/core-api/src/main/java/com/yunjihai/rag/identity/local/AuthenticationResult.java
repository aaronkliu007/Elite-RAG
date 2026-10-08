package com.yunjihai.rag.identity.local;

import java.util.UUID;

public record AuthenticationResult(UUID userId, String username, String displayName) {
}
