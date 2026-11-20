package com.yunjihai.rag.knowledge.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.PermissionGrantQuery;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@ConditionalOnBean({PermissionGrantQuery.class, KnowledgeWriteAuthorization.class})
public class PermissionOverviewController {
    private final PermissionGrantQuery grants;
    private final KnowledgeWriteAuthorization authorization;
    public PermissionOverviewController(PermissionGrantQuery grants, KnowledgeWriteAuthorization authorization) {
        this.grants = Objects.requireNonNull(grants);
        this.authorization = Objects.requireNonNull(authorization);
    }

    @GetMapping("/api/v1/knowledge-spaces/{spaceId}/permission-grants")
    public List<PermissionGrantQuery.Grant> list(Authentication authentication, @PathVariable UUID spaceId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "100") int pageSize) {
        UUID actor;
        try { actor = UUID.fromString(authentication.getName()); }
        catch (RuntimeException failure) { throw new AccessDeniedAsNotFoundException(); }
        authorization.require(actor, spaceId, null, KnowledgeWriteAuthorization.Action.MANAGE_ACL);
        if (page < 1 || pageSize < 1 || pageSize > 100 || (long) (page - 1) * pageSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("分页参数不正确");
        }
        return grants.listForSpace(spaceId, (page - 1) * pageSize, pageSize);
    }
}
