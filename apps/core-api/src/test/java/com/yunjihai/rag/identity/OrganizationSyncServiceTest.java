package com.yunjihai.rag.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizationSyncServiceTest {

    @Test
    @DisplayName("完整快照原子对账并撤销被停用用户的全部旧会话")
    void reconcilesCompleteSnapshotAndRevokesDisabledUsers() {
        var organizationStore = new RecordingOrganizationStore(Set.of("u-disabled", "u-removed"));
        var sessionStore = new InMemorySessionVersionStore();
        var sessions = new SessionRevocationService(sessionStore);
        var oldSession = sessions.issue("u-disabled");
        var service = new OrganizationSyncService(organizationStore, sessions);
        var snapshot = new OrganizationSnapshot(
                List.of(new ExternalDepartment("d-1", null, "研发中心")),
                List.of(new ExternalUser("u-active", "李四", true, Set.of("d-1"))),
                Instant.parse("2026-09-24T08:00:00Z"),
                true);

        service.synchronize(snapshot);

        assertThat(organizationStore.lastSnapshot).isEqualTo(snapshot);
        assertThatThrownBy(() -> sessions.assertActive(oldSession))
                .isInstanceOf(DisabledUserSessionException.class);
    }

    @Test
    @DisplayName("不完整快照不得触发删除式对账")
    void refusesIncompleteSnapshotBeforeWritingAnything() {
        var organizationStore = new RecordingOrganizationStore(Set.of());
        var service = new OrganizationSyncService(
                organizationStore,
                new SessionRevocationService(new InMemorySessionVersionStore()));
        var snapshot = new OrganizationSnapshot(List.of(), List.of(), Instant.now(), false);

        assertThatThrownBy(() -> service.synchronize(snapshot))
                .isInstanceOf(IncompleteOrganizationSnapshotException.class);
        assertThat(organizationStore.lastSnapshot).isNull();
    }

    @Test
    @DisplayName("引用未知部门的用户会使整个快照失效")
    void rejectsUserMembershipInUnknownDepartment() {
        var organizationStore = new RecordingOrganizationStore(Set.of());
        var service = new OrganizationSyncService(
                organizationStore,
                new SessionRevocationService(new InMemorySessionVersionStore()));
        var snapshot = new OrganizationSnapshot(
                List.of(),
                List.of(new ExternalUser("u-1", "王五", true, Set.of("missing"))),
                Instant.now(),
                true);

        assertThatThrownBy(() -> service.synchronize(snapshot))
                .isInstanceOf(InvalidOrganizationSnapshotException.class);
        assertThat(organizationStore.lastSnapshot).isNull();
    }

    private static final class RecordingOrganizationStore implements OrganizationReconciliationPort {
        private final Set<String> disabledUserIds;
        private OrganizationSnapshot lastSnapshot;

        private RecordingOrganizationStore(Set<String> disabledUserIds) {
            this.disabledUserIds = disabledUserIds;
        }

        @Override
        public ReconciliationResult reconcile(OrganizationSnapshot snapshot) {
            lastSnapshot = snapshot;
            return new ReconciliationResult(disabledUserIds);
        }
    }
}
