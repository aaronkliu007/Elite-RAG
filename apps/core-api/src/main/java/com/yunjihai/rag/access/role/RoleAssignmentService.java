package com.yunjihai.rag.access.role;

import com.yunjihai.rag.access.AclCommandRepository;
import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PrincipalType;
import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class RoleAssignmentService {
    private final RoleAssignmentStore roles;
    private final AclCommandRepository acl;
    private final AuditSink audit;
    private final ProjectionChangePublisher projections;
    private final KnowledgeWriteAuthorization authorization;

    public RoleAssignmentService(
            RoleAssignmentStore roles,
            AclCommandRepository acl,
            AuditSink audit,
            ProjectionChangePublisher projections,
            KnowledgeWriteAuthorization authorization) {
        this.roles = Objects.requireNonNull(roles);
        this.acl = Objects.requireNonNull(acl);
        this.audit = Objects.requireNonNull(audit);
        this.projections = Objects.requireNonNull(projections);
        this.authorization = Objects.requireNonNull(authorization);
    }

    @Transactional(readOnly = true)
    public boolean hasSystemRole(UUID userId, SystemRole role) {
        return roles.hasSystemRole(Objects.requireNonNull(userId), Objects.requireNonNull(role));
    }

    @Transactional(readOnly = true)
    public boolean hasAnySystemRole(SystemRole role) {
        return roles.hasAnySystemRole(Objects.requireNonNull(role));
    }

    @Transactional(readOnly = true)
    public Set<SpaceRole> activeSpaceRoles(UUID userId, UUID spaceId) {
        return roles.activeSpaceRoles(Objects.requireNonNull(userId), Objects.requireNonNull(spaceId));
    }

    @Transactional
    public void grantSystemRole(UUID userId, SystemRole role, UUID actorId, Instant at) {
        requireCommon(userId, actorId, at);
        roles.grantSystemRole(userId, Objects.requireNonNull(role), actorId, at);
        record(actorId, "USER", userId, "SYSTEM_ROLE_GRANT:" + role, userId);
    }

    @Transactional
    public void grantSpaceRole(UUID actorId, UUID spaceId, UUID userId, SpaceRole role, Instant at) {
        requireSpaceCommand(actorId, spaceId, userId, role, at);
        projections.withSpaceChangeLock(spaceId, () -> {
            authorization.require(actorId, spaceId, null, KnowledgeWriteAuthorization.Action.MANAGE_ACL);
            boolean firstRole = roles.activeSpaceRoles(userId, spaceId).isEmpty();
            roles.grantSpaceRole(spaceId, userId, role, actorId, at);
            if (firstRole) {
                acl.grantSpace(spaceId, user(userId), null, at);
            }
            record(actorId, "KNOWLEDGE_SPACE", spaceId, "SPACE_ROLE_GRANT:" + role, userId);
            projections.spaceAclChanged(spaceId, at);
            return null;
        });
    }

    @Transactional
    public void revokeSpaceRole(UUID actorId, UUID spaceId, UUID userId, SpaceRole role, Instant at) {
        requireSpaceCommand(actorId, spaceId, userId, role, at);
        projections.withSpaceChangeLock(spaceId, () -> {
            authorization.require(actorId, spaceId, null, KnowledgeWriteAuthorization.Action.MANAGE_ACL);
            roles.revokeSpaceRole(spaceId, userId, role, actorId, at);
            if (roles.activeSpaceRoles(userId, spaceId).isEmpty()) {
                acl.revokeSpace(spaceId, user(userId), at);
            }
            record(actorId, "KNOWLEDGE_SPACE", spaceId, "SPACE_ROLE_REVOKE:" + role, userId);
            projections.spaceAclChanged(spaceId, at);
            return null;
        });
    }

    private static PrincipalRef user(UUID userId) {
        return new PrincipalRef(PrincipalType.USER, userId);
    }

    private static void requireCommon(UUID userId, UUID actorId, Instant at) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(at);
    }

    private static void requireSpaceCommand(
            UUID actorId, UUID spaceId, UUID userId, SpaceRole role, Instant at) {
        requireCommon(userId, actorId, at);
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(role);
    }

    private void record(UUID actorId, String resourceType, UUID resourceId, String scope, UUID targetUserId) {
        audit.record(new AuditCommand(
                actorId,
                AuditAction.ROLE_CHANGE,
                resourceType,
                resourceId,
                AuditOutcome.SUCCEEDED,
                Map.of(
                        "permissionScope", scope,
                        "targetPrincipalType", PrincipalType.USER.name(),
                        "targetPrincipalId", targetUserId.toString())));
    }
}
