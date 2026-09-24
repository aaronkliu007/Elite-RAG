package com.yunjihai.rag.identity;

import java.util.Set;

public record ExternalUser(
        String externalId,
        String displayName,
        boolean active,
        Set<String> departmentExternalIds) {

    public ExternalUser {
        departmentExternalIds = Set.copyOf(departmentExternalIds);
    }
}
