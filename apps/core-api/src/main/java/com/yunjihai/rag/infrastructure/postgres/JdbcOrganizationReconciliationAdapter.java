package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.common.UuidV7;
import com.yunjihai.rag.identity.ExternalDepartment;
import com.yunjihai.rag.identity.ExternalUser;
import com.yunjihai.rag.identity.IncompleteOrganizationSnapshotException;
import com.yunjihai.rag.identity.InvalidOrganizationSnapshotException;
import com.yunjihai.rag.identity.OrganizationReconciliationPort;
import com.yunjihai.rag.identity.OrganizationSnapshot;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Replaces one complete upstream organization snapshot inside the caller's transaction. */
public class JdbcOrganizationReconciliationAdapter implements OrganizationReconciliationPort {
    public static final int MAX_EXTERNAL_ID_LENGTH = 255;
    private static final int ROWS_PER_STATEMENT = 500;
    private final JdbcClient jdbc;
    private final String sourcePrefix;

    public JdbcOrganizationReconciliationAdapter(JdbcClient jdbc) {
        this(jdbc, null);
    }

    public JdbcOrganizationReconciliationAdapter(JdbcClient jdbc, String sourcePrefix) {
        this.jdbc = Objects.requireNonNull(jdbc);
        if (sourcePrefix != null && sourcePrefix.isBlank()) throw new IllegalArgumentException("组织来源前缀不能为空");
        this.sourcePrefix = sourcePrefix;
    }

    @Override
    @Transactional
    public ReconciliationResult reconcile(OrganizationSnapshot snapshot) {
        validate(snapshot);
        if (sourcePrefix != null && (snapshot.departments().stream().anyMatch(d -> !d.externalId().startsWith(sourcePrefix))
                || snapshot.users().stream().anyMatch(u -> !u.externalId().startsWith(sourcePrefix)))) {
            throw new InvalidOrganizationSnapshotException("组织快照包含非当前来源的标识");
        }
        try {
            // The lock lasts through OrganizationSyncService.disable, so two imports cannot interleave.
            jdbc.sql("SELECT pg_advisory_xact_lock(20260929, 3)").query((rs, row) -> 1).single();
            var previouslyEnabled = enabledUserIdsByExternalId();
            stageSnapshotIds(snapshot);
            upsertDepartments(snapshot);
            var departmentIds = ids("org_unit");
            updateParents(snapshot, departmentIds);
            upsertUsers(snapshot);
            var userIds = ids("app_user");
            replaceMemberships(snapshot, userIds, departmentIds);
            disableMissingUsers();
            disableMissingDepartments();
            var active = new HashSet<String>();
            for (var user : snapshot.users()) {
                if (user.active()) active.add(user.externalId());
            }
            previouslyEnabled.keySet().removeAll(active);
            return new ReconciliationResult(Set.copyOf(previouslyEnabled.values()));
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private void stageSnapshotIds(OrganizationSnapshot snapshot) {
        jdbc.sql("CREATE TEMP TABLE IF NOT EXISTS rag_org_snapshot_ids "
                + "(kind char(1) NOT NULL, external_id text NOT NULL, "
                + "PRIMARY KEY (kind, external_id)) ON COMMIT DROP").update();
        jdbc.sql("TRUNCATE rag_org_snapshot_ids").update();
        var batch = new ValueBatch();
        for (var department : snapshot.departments()) {
            batch.add("D", department.externalId());
            if (batch.size() == ROWS_PER_STATEMENT) {
                insertSnapshotIds(batch);
                batch.clear();
            }
        }
        for (var user : snapshot.users()) {
            if (!user.active()) continue;
            batch.add("U", user.externalId());
            if (batch.size() == ROWS_PER_STATEMENT) {
                insertSnapshotIds(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) insertSnapshotIds(batch);
    }

    private void insertSnapshotIds(ValueBatch batch) {
        jdbc.sql("INSERT INTO rag_org_snapshot_ids (kind, external_id) VALUES " + batch.sql())
                .params(batch.params()).update();
    }

    private void upsertDepartments(OrganizationSnapshot snapshot) {
        if (snapshot.departments().isEmpty()) return;
        var batch = new ValueBatch();
        for (var department : snapshot.departments()) {
            batch.add(UuidV7.generate(snapshot.capturedAt()), department.externalId(), department.name());
            if (batch.size() == ROWS_PER_STATEMENT) {
                upsertDepartmentBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) upsertDepartmentBatch(batch);
    }

    private void upsertDepartmentBatch(ValueBatch batch) {
        jdbc.sql("INSERT INTO org_unit (id, external_id, name) VALUES " + batch.sql()
                        + " ON CONFLICT (external_id) DO UPDATE SET name = EXCLUDED.name, "
                        + "enabled = true, updated_at = now()")
                .params(batch.params()).update();
    }

    private void updateParents(OrganizationSnapshot snapshot, Map<String, UUID> departmentIds) {
        if (snapshot.departments().isEmpty()) return;
        var batch = new ValueBatch();
        for (var department : snapshot.departments()) {
            batch.add(department.externalId(), department.parentExternalId() == null
                    ? null : departmentIds.get(department.parentExternalId()));
            if (batch.size() == ROWS_PER_STATEMENT) {
                updateParentBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) updateParentBatch(batch);
    }

    private void updateParentBatch(ValueBatch batch) {
        jdbc.sql("UPDATE org_unit AS target SET parent_id = source.parent_id, updated_at = now() "
                        + "FROM (VALUES " + batch.sqlWithUuidSecondColumn() + ") AS source(external_id, parent_id) "
                        + "WHERE target.external_id = source.external_id")
                .params(batch.params()).update();
    }

    private void upsertUsers(OrganizationSnapshot snapshot) {
        if (snapshot.users().isEmpty()) return;
        var batch = new ValueBatch();
        for (var user : snapshot.users()) {
            batch.add(UuidV7.generate(snapshot.capturedAt()), user.externalId(), user.displayName(), user.active());
            if (batch.size() == ROWS_PER_STATEMENT) {
                upsertUserBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) upsertUserBatch(batch);
    }

    private void upsertUserBatch(ValueBatch batch) {
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name, enabled) VALUES " + batch.sql()
                        + " ON CONFLICT (external_id) DO UPDATE SET display_name = EXCLUDED.display_name, "
                        + "enabled = EXCLUDED.enabled, updated_at = now()")
                .params(batch.params()).update();
    }

    private void replaceMemberships(OrganizationSnapshot snapshot, Map<String, UUID> userIds,
                                    Map<String, UUID> departmentIds) {
        if (sourcePrefix == null) jdbc.sql("DELETE FROM org_membership").update();
        else jdbc.sql("DELETE FROM org_membership WHERE user_id IN (SELECT id FROM app_user WHERE left(external_id, length(:prefix)) = :prefix)")
                .param("prefix", sourcePrefix).update();
        var batch = new ValueBatch();
        OffsetDateTime capturedAt = OffsetDateTime.ofInstant(snapshot.capturedAt(), ZoneOffset.UTC);
        for (var user : snapshot.users()) {
            if (!user.active()) continue;
            for (var department : user.departmentExternalIds()) {
                batch.add(userIds.get(user.externalId()), departmentIds.get(department), capturedAt);
                if (batch.size() == ROWS_PER_STATEMENT) {
                    insertMembershipBatch(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) insertMembershipBatch(batch);
    }

    private void insertMembershipBatch(ValueBatch batch) {
        jdbc.sql("INSERT INTO org_membership (user_id, org_unit_id, snapshot_at) VALUES " + batch.sql())
                .params(batch.params()).update();
    }

    private void disableMissingUsers() {
        var statement = jdbc.sql("UPDATE app_user AS target SET enabled = false, updated_at = now() "
                + "WHERE enabled AND NOT EXISTS (SELECT 1 FROM rag_org_snapshot_ids AS snapshot "
                + "WHERE snapshot.kind = 'U' AND snapshot.external_id = target.external_id)"
                + (sourcePrefix == null ? "" : " AND left(target.external_id, length(:prefix)) = :prefix"));
        if (sourcePrefix != null) statement = statement.param("prefix", sourcePrefix);
        statement.update();
    }

    private void disableMissingDepartments() {
        var statement = jdbc.sql("UPDATE org_unit AS target SET enabled = false, updated_at = now() "
                + "WHERE enabled AND NOT EXISTS (SELECT 1 FROM rag_org_snapshot_ids AS snapshot "
                + "WHERE snapshot.kind = 'D' AND snapshot.external_id = target.external_id)"
                + (sourcePrefix == null ? "" : " AND left(target.external_id, length(:prefix)) = :prefix"));
        if (sourcePrefix != null) statement = statement.param("prefix", sourcePrefix);
        statement.update();
    }

    private Map<String, UUID> ids(String table) {
        var result = new HashMap<String, UUID>();
        jdbc.sql("SELECT external_id, id FROM " + table).query((rs, row) -> {
            result.put(rs.getString("external_id"), rs.getObject("id", UUID.class));
            return 1;
        }).list();
        return result;
    }

    private Map<String, UUID> enabledUserIdsByExternalId() {
        var result = new HashMap<String, UUID>();
        var statement = jdbc.sql("SELECT external_id, id FROM app_user WHERE enabled"
                + (sourcePrefix == null ? "" : " AND left(external_id, length(:prefix)) = :prefix"));
        if (sourcePrefix != null) statement = statement.param("prefix", sourcePrefix);
        statement.query((rs, row) -> {
            result.put(rs.getString("external_id"), rs.getObject("id", UUID.class));
            return 1;
        }).list();
        return result;
    }

    private static void validate(OrganizationSnapshot snapshot) {
        if (snapshot == null || !snapshot.complete()) {
            throw new IncompleteOrganizationSnapshotException("上游组织快照不完整，已拒绝对账");
        }
        if (snapshot.capturedAt() == null) {
            throw new InvalidOrganizationSnapshotException("组织快照缺少采集时间");
        }
        // UUIDv7 generation must not fail after SQL writes have started.
        try {
            UuidV7.generate(snapshot.capturedAt());
        } catch (IllegalArgumentException failure) {
            throw new InvalidOrganizationSnapshotException("组织快照采集时间无效");
        }
        var departments = new HashMap<String, String>();
        var departmentIds = new HashSet<String>();
        for (var department : snapshot.departments()) {
            requireId(department.externalId());
            requireName(department.name());
            if (department.parentExternalId() != null) requireId(department.parentExternalId());
            if (!departmentIds.add(department.externalId())) {
                throw new InvalidOrganizationSnapshotException("组织快照包含重复部门");
            }
            departments.put(department.externalId(), department.parentExternalId());
        }
        for (var department : snapshot.departments()) {
            var path = new HashSet<String>();
            String current = department.externalId();
            while (current != null) {
                if (!departments.containsKey(current)) {
                    throw new InvalidOrganizationSnapshotException("部门引用了未知父部门");
                }
                if (!path.add(current)) {
                    throw new InvalidOrganizationSnapshotException("部门父级关系包含环");
                }
                current = departments.get(current);
            }
        }
        var users = new HashSet<String>();
        for (var user : snapshot.users()) {
            requireId(user.externalId());
            requireName(user.displayName());
            if (!users.add(user.externalId())) {
                throw new InvalidOrganizationSnapshotException("组织快照包含重复用户");
            }
            for (var departmentId : user.departmentExternalIds()) {
                requireId(departmentId);
                if (!departments.containsKey(departmentId)) {
                    throw new InvalidOrganizationSnapshotException("用户引用了未知部门");
                }
            }
        }
    }

    private static void requireId(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_EXTERNAL_ID_LENGTH) {
            throw new InvalidOrganizationSnapshotException("externalId 无效");
        }
    }

    private static void requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidOrganizationSnapshotException("组织名称不能为空");
        }
    }

    private static final class ValueBatch {
        private final List<List<Object>> rows = new ArrayList<>();
        private final Map<String, Object> params = new HashMap<>();

        void add(Object... values) {
            int row = rows.size();
            rows.add(java.util.Arrays.asList(values.clone()));
            for (int column = 0; column < values.length; column++) {
                params.put("p" + row + "_" + column, values[column]);
            }
        }

        boolean isEmpty() { return rows.isEmpty(); }
        int size() { return rows.size(); }
        void clear() { rows.clear(); params.clear(); }
        Map<String, Object> params() { return params; }
        String sql() { return sql(false); }
        String sqlWithUuidSecondColumn() { return sql(true); }

        private String sql(boolean castSecondAsUuid) {
            var values = new ArrayList<String>();
            for (int row = 0; row < rows.size(); row++) {
                var columns = new ArrayList<String>();
                for (int column = 0; column < rows.get(row).size(); column++) {
                    String parameter = ":p" + row + "_" + column;
                    columns.add(castSecondAsUuid && column == 1 ? "CAST(" + parameter + " AS uuid)" : parameter);
                }
                values.add("(" + String.join(", ", columns) + ")");
            }
            return String.join(", ", values);
        }
    }
}
