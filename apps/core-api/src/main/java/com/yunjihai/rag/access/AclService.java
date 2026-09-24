package com.yunjihai.rag.access;

import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class AclService {

    private final AclCommandRepository repository;
    private final KnowledgeWriteAuthorization authorization;
    private final AuditSink audit;

    public AclService(
            AclCommandRepository repository,
            KnowledgeWriteAuthorization authorization,
            AuditSink audit) {
        this.repository = Objects.requireNonNull(repository);
        this.authorization = Objects.requireNonNull(authorization);
        this.audit = Objects.requireNonNull(audit);
    }

    @Transactional
    public void grantSpace(
            UUID actorId,
            UUID spaceId,
            PrincipalRef principal,
            Instant validUntil,
            Instant at) {
        requireCommand(actorId, spaceId, null, principal, validUntil, at);
        repository.grantSpace(spaceId, principal, validUntil, at);
        record(actorId, spaceId, null, principal, "SPACE_GRANT");
    }

    @Transactional
    public void grantDocument(
            UUID actorId,
            UUID spaceId,
            UUID documentId,
            PrincipalRef principal,
            Instant validUntil,
            Instant at) {
        requireCommand(actorId, spaceId, documentId, principal, validUntil, at);
        if (!repository.isWithinSpaceScope(spaceId, principal, at)) {
            throw new IllegalArgumentException("文档授权不能突破知识空间范围");
        }
        repository.grantDocument(spaceId, documentId, principal, validUntil, at);
        record(actorId, spaceId, documentId, principal, "DOCUMENT_GRANT");
    }

    @Transactional
    public void revokeSpace(
            UUID actorId,
            UUID spaceId,
            PrincipalRef principal,
            Instant at) {
        requireCommand(actorId, spaceId, null, principal, null, at);
        repository.revokeSpace(spaceId, principal, at);
        record(actorId, spaceId, null, principal, "SPACE_REVOKE");
    }

    @Transactional
    public void revokeDocument(
            UUID actorId,
            UUID spaceId,
            UUID documentId,
            PrincipalRef principal,
            Instant at) {
        requireCommand(actorId, spaceId, documentId, principal, null, at);
        repository.revokeDocument(spaceId, documentId, principal, at);
        record(actorId, spaceId, documentId, principal, "DOCUMENT_REVOKE");
    }

    private void requireCommand(
            UUID actorId,
            UUID spaceId,
            UUID documentId,
            PrincipalRef principal,
            Instant validUntil,
            Instant at) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(principal);
        Objects.requireNonNull(at);
        if (validUntil != null && !validUntil.isAfter(at)) {
            throw new IllegalArgumentException("授权有效期必须晚于生效时间");
        }
        authorization.require(
                actorId, spaceId, documentId, KnowledgeWriteAuthorization.Action.MANAGE_ACL);
    }

    private void record(
            UUID actorId,
            UUID spaceId,
            UUID documentId,
            PrincipalRef principal,
            String scope) {
        audit.record(new AuditCommand(
                actorId,
                AuditAction.ACL_CHANGE,
                documentId == null ? "KNOWLEDGE_SPACE" : "DOCUMENT",
                documentId == null ? spaceId : documentId,
                AuditOutcome.SUCCEEDED,
                Map.of(
                        "spaceId", spaceId.toString(),
                        "permissionScope", scope,
                        "targetPrincipalType", principal.type().name(),
                        "targetPrincipalId", principal.id().toString())));
    }
}
