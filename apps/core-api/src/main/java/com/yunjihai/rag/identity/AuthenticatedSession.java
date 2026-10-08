package com.yunjihai.rag.identity;

import java.util.UUID;

public record AuthenticatedSession(UUID userId, long version) {
}
