package com.yunjihai.rag.identity;

public interface SessionVersionStore {

    long currentVersion(String userExternalId);

    long advanceVersion(String userExternalId);
}
