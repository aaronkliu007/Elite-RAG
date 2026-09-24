package com.yunjihai.rag.identity;

import java.util.Set;

public interface OrganizationReconciliationPort {

    ReconciliationResult reconcile(OrganizationSnapshot snapshot);

    record ReconciliationResult(Set<String> disabledOrRemovedUserIds) {
        public ReconciliationResult {
            disabledOrRemovedUserIds = Set.copyOf(disabledOrRemovedUserIds);
        }
    }
}
