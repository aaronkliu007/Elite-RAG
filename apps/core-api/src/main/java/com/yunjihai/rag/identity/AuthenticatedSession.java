package com.yunjihai.rag.identity;

public record AuthenticatedSession(String userExternalId, long version) {
}
