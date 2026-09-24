package com.yunjihai.rag.identity;

import java.time.Instant;
import java.util.List;

public record OrganizationSnapshot(
        List<ExternalDepartment> departments,
        List<ExternalUser> users,
        Instant capturedAt,
        boolean complete) {

    public OrganizationSnapshot {
        departments = List.copyOf(departments);
        users = List.copyOf(users);
    }
}
