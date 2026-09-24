package com.yunjihai.rag.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EffectivePrincipalAccessTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID DEPARTMENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000010");
    private static final UUID PARENT_DEPARTMENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000011");
    private static final UUID ROOT_DEPARTMENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000012");
    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-7000-8000-000000000020");
    private static final UUID PROJECT_GROUP_ID = UUID.fromString("00000000-0000-7000-8000-000000000030");

    @Test
    void expandsUserDepartmentsAncestorsRolesAndProjectGroups() {
        PrincipalMembershipRepository repository = userId -> new PrincipalMembershipSnapshot(
                Set.of(DEPARTMENT_ID),
                Map.of(
                        DEPARTMENT_ID, PARENT_DEPARTMENT_ID,
                        PARENT_DEPARTMENT_ID, ROOT_DEPARTMENT_ID),
                Set.of(ROLE_ID),
                Set.of(PROJECT_GROUP_ID));
        EffectivePrincipalSet principalSet = new EffectivePrincipalSet(repository);

        assertThat(principalSet.forUser(USER_ID)).containsExactlyInAnyOrder(
                new PrincipalRef(PrincipalType.USER, USER_ID),
                new PrincipalRef(PrincipalType.DEPARTMENT, DEPARTMENT_ID),
                new PrincipalRef(PrincipalType.DEPARTMENT, PARENT_DEPARTMENT_ID),
                new PrincipalRef(PrincipalType.DEPARTMENT, ROOT_DEPARTMENT_ID),
                new PrincipalRef(PrincipalType.ROLE, ROLE_ID),
                new PrincipalRef(PrincipalType.PROJECT_GROUP, PROJECT_GROUP_ID));
    }

    @Test
    void stopsAtDepartmentCycleWithoutDroppingKnownPrincipals() {
        PrincipalMembershipRepository repository = userId -> new PrincipalMembershipSnapshot(
                Set.of(DEPARTMENT_ID),
                Map.of(
                        DEPARTMENT_ID, PARENT_DEPARTMENT_ID,
                        PARENT_DEPARTMENT_ID, DEPARTMENT_ID),
                Set.of(),
                Set.of());

        assertThat(new EffectivePrincipalSet(repository).forUser(USER_ID)).containsExactlyInAnyOrder(
                new PrincipalRef(PrincipalType.USER, USER_ID),
                new PrincipalRef(PrincipalType.DEPARTMENT, DEPARTMENT_ID),
                new PrincipalRef(PrincipalType.DEPARTMENT, PARENT_DEPARTMENT_ID));
    }
}
