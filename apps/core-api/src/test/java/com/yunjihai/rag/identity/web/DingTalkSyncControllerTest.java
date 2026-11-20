package com.yunjihai.rag.identity.web;

import com.yunjihai.rag.access.AclCommandRepository;
import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.role.*;
import com.yunjihai.rag.identity.DingTalkOrganizationSynchronizer;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class DingTalkSyncControllerTest {
    @Test void onlySystemAdministratorCanInspectOrTriggerDisabledSync() {
        UUID admin = UUID.randomUUID(), ordinary = UUID.randomUUID();
        RoleAssignmentStore store = new RoleAssignmentStore() {
            public boolean hasSystemRole(UUID id, SystemRole role) { return id.equals(admin); }
            public boolean hasAnySystemRole(SystemRole role) { return true; }
            public Set<SpaceRole> activeSpaceRoles(UUID id, UUID space) { return Set.of(); }
            public void grantSystemRole(UUID id, SystemRole role, UUID actor, Instant at) { }
            public void grantSpaceRole(UUID space, UUID id, SpaceRole role, UUID actor, Instant at) { }
            public void revokeSpaceRole(UUID space, UUID id, SpaceRole role, UUID actor, Instant at) { }
        };
        AclCommandRepository acl = new AclCommandRepository() {
            public boolean isWithinSpaceScope(UUID s, PrincipalRef p, Instant at) { return false; }
            public void grantSpace(UUID s, PrincipalRef p, Instant until, Instant at) { }
            public void grantDocument(UUID s, UUID d, PrincipalRef p, Instant until, Instant at) { }
            public void revokeSpace(UUID s, PrincipalRef p, Instant at) { }
            public void revokeDocument(UUID s, UUID d, PrincipalRef p, Instant at) { }
        };
        var roles = new RoleAssignmentService(store, acl, command -> null, ProjectionChangePublisher.noop(), KnowledgeWriteAuthorization.allowAll());
        var provider = new DefaultListableBeanFactory().getBeanProvider(DingTalkOrganizationSynchronizer.class);
        var controller = new DingTalkSyncController(provider, roles, null, false);
        var authorized = new UsernamePasswordAuthenticationToken(admin.toString(), "unused");
        var denied = new UsernamePasswordAuthenticationToken(ordinary.toString(), "unused");
        assertThat(controller.status(authorized).configured()).isFalse();
        assertThat(controller.preview(authorized).getStatusCode().value()).isEqualTo(503);
        assertThat(controller.synchronize(authorized).getStatusCode().value()).isEqualTo(409);
        assertThatThrownBy(() -> controller.status(denied)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.synchronize(denied)).isInstanceOf(AccessDeniedException.class);
    }
}
