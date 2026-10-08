package com.yunjihai.rag.identity;

import java.util.UUID;

public final class SessionRevocationService {

    private final SessionVersionStore store;

    public SessionRevocationService(SessionVersionStore store) {
        this.store = store;
    }

    public AuthenticatedSession issue(UUID userId) {
        requireEnabled(userId);
        return new AuthenticatedSession(userId, store.currentVersion(userId));
    }

    public void revokeAll(UUID userId) {
        store.advanceVersion(userId);
    }

    public void disable(UUID userId) {
        store.setEnabled(userId, false);
        store.advanceVersion(userId);
    }

    public void assertActive(AuthenticatedSession session) {
        requireEnabled(session.userId());
        if (store.currentVersion(session.userId()) != session.version()) {
            throw new DisabledUserSessionException();
        }
    }

    private void requireEnabled(UUID userId) {
        if (!store.isEnabled(userId)) {
            throw new DisabledUserSessionException();
        }
    }
}
