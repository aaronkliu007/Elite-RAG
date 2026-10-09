package com.yunjihai.rag.identity.local;

import java.util.UUID;

@FunctionalInterface
public interface SystemAdministratorDisableProtection {
    void requireCanDisable(UUID targetUserId, UUID actorId);

    static SystemAdministratorDisableProtection allowAll() {
        return (targetUserId, actorId) -> { };
    }
}
