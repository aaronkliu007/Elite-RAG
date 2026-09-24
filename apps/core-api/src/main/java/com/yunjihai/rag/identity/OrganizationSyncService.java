package com.yunjihai.rag.identity;

import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class OrganizationSyncService {

    private final OrganizationReconciliationPort organizationStore;
    private final SessionRevocationService sessions;

    public OrganizationSyncService(
            OrganizationReconciliationPort organizationStore,
            SessionRevocationService sessions) {
        this.organizationStore = organizationStore;
        this.sessions = sessions;
    }

    @Transactional
    public void synchronize(OrganizationSnapshot snapshot) {
        validate(snapshot);
        var result = organizationStore.reconcile(snapshot);
        result.disabledOrRemovedUserIds().forEach(sessions::disable);
    }

    private static void validate(OrganizationSnapshot snapshot) {
        if (snapshot == null || !snapshot.complete()) {
            throw new IncompleteOrganizationSnapshotException("上游组织快照不完整，已拒绝对账");
        }
        if (snapshot.capturedAt() == null) {
            throw new InvalidOrganizationSnapshotException("组织快照缺少采集时间");
        }

        var departmentIds = uniqueDepartmentIds(snapshot);
        for (var department : snapshot.departments()) {
            var parentId = department.parentExternalId();
            if (parentId != null && !parentId.isBlank() && !departmentIds.contains(parentId)) {
                throw new InvalidOrganizationSnapshotException("部门引用了未知父部门");
            }
            if (department.externalId().equals(parentId)) {
                throw new InvalidOrganizationSnapshotException("部门不能以自身作为父部门");
            }
        }
        rejectDepartmentCycles(snapshot);

        var userIds = new HashSet<String>();
        for (var user : snapshot.users()) {
            requireText(user.externalId(), "用户 externalId");
            if (!userIds.add(user.externalId())) {
                throw new InvalidOrganizationSnapshotException("组织快照包含重复用户");
            }
            if (!departmentIds.containsAll(user.departmentExternalIds())) {
                throw new InvalidOrganizationSnapshotException("用户引用了未知部门");
            }
        }
    }

    private static void rejectDepartmentCycles(OrganizationSnapshot snapshot) {
        Map<String, String> parentByChild = snapshot.departments().stream()
                .filter(department -> department.parentExternalId() != null
                        && !department.parentExternalId().isBlank())
                .collect(Collectors.toMap(
                        ExternalDepartment::externalId,
                        ExternalDepartment::parentExternalId));
        for (var department : snapshot.departments()) {
            var path = new HashSet<String>();
            var current = department.externalId();
            while (current != null && !current.isBlank()) {
                if (!path.add(current)) {
                    throw new InvalidOrganizationSnapshotException("部门父级关系包含环");
                }
                current = parentByChild.get(current);
            }
        }
    }

    private static Set<String> uniqueDepartmentIds(OrganizationSnapshot snapshot) {
        var ids = new HashSet<String>();
        for (var department : snapshot.departments()) {
            requireText(department.externalId(), "部门 externalId");
            if (!ids.add(department.externalId())) {
                throw new InvalidOrganizationSnapshotException("组织快照包含重复部门");
            }
        }
        return ids;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidOrganizationSnapshotException(field + " 不能为空");
        }
    }
}
