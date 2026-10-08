package com.yunjihai.rag.identity;

import java.util.Set;
import java.util.UUID;

public interface OrganizationReconciliationPort {

    ReconciliationResult reconcile(OrganizationSnapshot snapshot);

    record ReconciliationResult(Set<UUID> disabledOrRemovedUserIds) {
        public ReconciliationResult {
            disabledOrRemovedUserIds = Set.copyOf(disabledOrRemovedUserIds);
        }
    }
}
