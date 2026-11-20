package com.yunjihai.rag.knowledge.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.EffectiveUserPermissionService;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@ConditionalOnBean({EffectiveUserPermissionService.class, KnowledgeWriteAuthorization.class})
public class EffectiveUserPermissionController {
    private final EffectiveUserPermissionService permissions;
    private final KnowledgeWriteAuthorization authorization;
    public EffectiveUserPermissionController(EffectiveUserPermissionService permissions, KnowledgeWriteAuthorization authorization) {
        this.permissions = permissions; this.authorization = authorization;
    }
    @GetMapping("/api/v1/knowledge-spaces/{spaceId}/users/{userId}/effective-permissions")
    public EffectiveUserPermissionService.Result get(Authentication authentication, @PathVariable UUID spaceId, @PathVariable UUID userId) {
        UUID actor;
        try { actor = UUID.fromString(authentication.getName()); }
        catch (RuntimeException failure) { throw new AccessDeniedAsNotFoundException(); }
        authorization.require(actor, spaceId, null, KnowledgeWriteAuthorization.Action.MANAGE_ACL);
        return permissions.forUser(spaceId, userId);
    }
}
