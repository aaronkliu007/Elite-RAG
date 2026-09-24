package com.yunjihai.rag.identity;

public final class SessionRevocationService {

    private final SessionVersionStore store;

    public SessionRevocationService(SessionVersionStore store) {
        this.store = store;
    }

    public AuthenticatedSession issue(String userExternalId) {
        requireEnabled(userExternalId);
        return new AuthenticatedSession(userExternalId, store.currentVersion(userExternalId));
    }

    public void revokeAll(String userExternalId) {
        store.advanceVersion(userExternalId);
    }

    public void disable(String userExternalId) {
        store.setEnabled(userExternalId, false);
        store.advanceVersion(userExternalId);
    }

    public void assertActive(AuthenticatedSession session) {
        requireEnabled(session.userExternalId());
        if (store.currentVersion(session.userExternalId()) != session.version()) {
            throw new DisabledUserSessionException();
        }
    }

    private void requireEnabled(String userExternalId) {
        if (!store.isEnabled(userExternalId)) {
            throw new DisabledUserSessionException();
        }
    }
}
