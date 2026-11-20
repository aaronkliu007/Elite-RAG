package com.yunjihai.rag.identity;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class DingTalkOrganizationCollectorTest {
    @Test void mergesMultiDepartmentUsersAndNamespacesAllIds() {
        DingTalkClient client = new DingTalkClient() {
            public ExternalIdentity exchangeAuthorizationCode(String code) { throw new UnsupportedOperationException(); }
            public List<ExternalDepartment> listDepartments() { return List.of(new ExternalDepartment("1", null, "公司"), new ExternalDepartment("2", "1", "研发")); }
            public List<ExternalUser> listUsers(String department) { return List.of(new ExternalUser("u1", "张三", true, Set.of(department))); }
        };
        var result = new DingTalkOrganizationCollector(client, Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC)).collect();
        assertThat(result.complete()).isTrue();
        assertThat(result.departments()).extracting(ExternalDepartment::externalId).containsExactly("dingtalk:dept:1", "dingtalk:dept:2");
        assertThat(result.users()).singleElement().satisfies(user -> {
            assertThat(user.externalId()).isEqualTo("dingtalk:user:u1");
            assertThat(user.departmentExternalIds()).containsExactlyInAnyOrder("dingtalk:dept:1", "dingtalk:dept:2");
        });
    }

    @Test void rejectsPartialOrContradictorySnapshots() {
        DingTalkClient failing = new DingTalkClient() {
            public ExternalIdentity exchangeAuthorizationCode(String code) { throw new UnsupportedOperationException(); }
            public List<ExternalDepartment> listDepartments() { return List.of(new ExternalDepartment("1", null, "公司"), new ExternalDepartment("2", "1", "研发")); }
            public List<ExternalUser> listUsers(String dept) { if (dept.equals("2")) throw new IllegalStateException("上游失败"); return List.of(); }
        };
        assertThatThrownBy(() -> new DingTalkOrganizationCollector(failing, Clock.systemUTC()).collect()).isInstanceOf(IllegalStateException.class);
        DingTalkClient empty = new DingTalkClient() {
            public ExternalIdentity exchangeAuthorizationCode(String code) { throw new UnsupportedOperationException(); }
            public List<ExternalDepartment> listDepartments() { return List.of(); }
            public List<ExternalUser> listUsers(String dept) { return List.of(); }
        };
        assertThatThrownBy(() -> new DingTalkOrganizationCollector(empty, Clock.systemUTC()).collect()).isInstanceOf(IncompleteOrganizationSnapshotException.class);
    }
}
