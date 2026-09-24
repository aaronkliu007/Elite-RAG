package com.yunjihai.rag.identity;

public interface SessionVersionStore {

    long currentVersion(String userExternalId);

    long advanceVersion(String userExternalId);

    boolean isEnabled(String userExternalId);

    void setEnabled(String userExternalId, boolean enabled);
}
