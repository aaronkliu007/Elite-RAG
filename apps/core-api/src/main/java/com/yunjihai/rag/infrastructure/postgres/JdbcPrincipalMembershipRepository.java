package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.access.PrincipalMembershipRepository;
import com.yunjihai.rag.access.PrincipalMembershipSnapshot;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Reads current organization membership and its enabled ancestor chain. */
public final class JdbcPrincipalMembershipRepository implements PrincipalMembershipRepository {
    private final JdbcClient jdbc;

    public JdbcPrincipalMembershipRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public PrincipalMembershipSnapshot findByUserId(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        try {
            var rows = jdbc.sql("""
                            WITH RECURSIVE department_tree AS (
                                SELECT membership.org_unit_id AS direct_id,
                                       department.id AS department_id,
                                       department.parent_id
                                FROM org_membership membership
                                JOIN app_user app_user
                                  ON app_user.id = membership.user_id
                                 AND app_user.enabled
                                JOIN org_unit department
                                  ON department.id = membership.org_unit_id
                                 AND department.enabled
                                WHERE membership.user_id = :userId
                                UNION
                                SELECT tree.direct_id,
                                       parent.id AS department_id,
                                       parent.parent_id
                                FROM department_tree tree
                                JOIN org_unit parent
                                  ON parent.id = tree.parent_id
                                 AND parent.enabled
                            )
                            SELECT direct_id, department_id, parent_id
                            FROM department_tree
                            """)
                    .param("userId", userId)
                    .query((resultSet, rowNumber) -> new DepartmentRow(
                            resultSet.getObject("direct_id", UUID.class),
                            resultSet.getObject("department_id", UUID.class),
                            resultSet.getObject("parent_id", UUID.class)))
                    .list();

            Set<UUID> directDepartments = new LinkedHashSet<>();
            Map<UUID, UUID> parents = new LinkedHashMap<>();
            Set<UUID> currentDepartments = new LinkedHashSet<>();
            rows.forEach(row -> currentDepartments.add(row.departmentId()));
            for (DepartmentRow row : rows) {
                directDepartments.add(row.directId());
                if (row.parentId() != null && currentDepartments.contains(row.parentId())) {
                    parents.put(row.departmentId(), row.parentId());
                }
            }
            // V001 has no authoritative role or project-group membership tables. Empty is safer
            // than manufacturing principals that could accidentally broaden authorization.
            return new PrincipalMembershipSnapshot(directDepartments, parents, Set.of(), Set.of());
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private record DepartmentRow(UUID directId, UUID departmentId, UUID parentId) {
    }
}
