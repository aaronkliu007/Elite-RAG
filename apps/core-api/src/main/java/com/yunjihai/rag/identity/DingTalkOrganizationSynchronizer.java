package com.yunjihai.rag.identity;

import java.util.Objects;
import java.util.function.Consumer;

/** Requires explicitly verified directory coverage before a snapshot can remove stale records. */
public final class DingTalkOrganizationSynchronizer {
    private final DingTalkOrganizationCollector collector;
    private final Consumer<OrganizationSnapshot> apply;
    private final boolean authoritativeScopeConfirmed;

    public DingTalkOrganizationSynchronizer(DingTalkOrganizationCollector collector,
            Consumer<OrganizationSnapshot> apply, boolean authoritativeScopeConfirmed) {
        this.collector = Objects.requireNonNull(collector); this.apply = Objects.requireNonNull(apply);
        this.authoritativeScopeConfirmed = authoritativeScopeConfirmed;
    }
    public OrganizationSnapshot preview() { return collector.collect(); }
    public OrganizationSnapshot synchronize() {
        if (!authoritativeScopeConfirmed) throw new IllegalStateException("尚未确认钉钉通讯录授权范围，无法执行全量对账");
        var snapshot = collector.collect();
        apply.accept(snapshot);
        return snapshot;
    }
}
