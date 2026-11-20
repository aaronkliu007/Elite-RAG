package com.yunjihai.rag.identity;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Collects every department before returning a complete snapshot; no database writes occur here. */
public final class DingTalkOrganizationCollector {
    private final DingTalkClient client;
    private final Clock clock;
    public DingTalkOrganizationCollector(DingTalkClient client, Clock clock) {
        this.client = Objects.requireNonNull(client); this.clock = Objects.requireNonNull(clock);
    }

    public OrganizationSnapshot collect() {
        var upstream = client.listDepartments();
        if (upstream == null || upstream.isEmpty()) throw new IncompleteOrganizationSnapshotException("钉钉部门列表为空，不能认定为完整快照");
        var departments = new ArrayList<ExternalDepartment>();
        var ids = new LinkedHashSet<String>();
        for (var dept : upstream) {
            String id = requireId(dept.externalId());
            if (!ids.add(id)) throw new InvalidOrganizationSnapshotException("钉钉部门标识重复");
            departments.add(new ExternalDepartment(departmentId(id), dept.parentExternalId() == null || dept.parentExternalId().isBlank() ? null : departmentId(dept.parentExternalId()), dept.name()));
        }
        var users = new LinkedHashMap<String, MutableUser>();
        for (String dept : ids) {
            var members = client.listUsers(dept);
            if (members == null) throw new IncompleteOrganizationSnapshotException("钉钉部门成员列表不完整");
            for (var user : members) {
                String id = requireId(user.externalId());
                if (user.displayName() == null || user.displayName().isBlank()) throw new InvalidOrganizationSnapshotException("钉钉用户缺少姓名");
                var existing = users.computeIfAbsent(id, ignored -> new MutableUser(user.displayName(), user.active()));
                if (!existing.name.equals(user.displayName()) || existing.active != user.active()) throw new InvalidOrganizationSnapshotException("钉钉用户多部门资料不一致");
                existing.departments.add(departmentId(dept));
                for (String memberDept : user.departmentExternalIds()) {
                    if (!ids.contains(memberDept)) throw new IncompleteOrganizationSnapshotException("钉钉用户引用未采集的部门");
                    existing.departments.add(departmentId(memberDept));
                }
            }
        }
        var mappedUsers = users.entrySet().stream().map(entry -> new ExternalUser("dingtalk:user:" + entry.getKey(),
                entry.getValue().name, entry.getValue().active, Set.copyOf(entry.getValue().departments))).toList();
        return new OrganizationSnapshot(List.copyOf(departments), mappedUsers, clock.instant(), true);
    }
    private static String requireId(String value) {
        if (value == null || value.isBlank() || value.length() > 200) throw new InvalidOrganizationSnapshotException("钉钉标识无效");
        return value;
    }
    private static String departmentId(String id) { return "dingtalk:dept:" + requireId(id); }
    private static final class MutableUser {
        final String name; final boolean active; final Set<String> departments = new LinkedHashSet<>();
        MutableUser(String name, boolean active) { this.name = name; this.active = active; }
    }
}
