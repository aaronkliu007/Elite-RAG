package com.yunjihai.rag.access;

import java.util.UUID;

@FunctionalInterface
public interface PrincipalMembershipRepository {

    PrincipalMembershipSnapshot findByUserId(UUID userId);
}
