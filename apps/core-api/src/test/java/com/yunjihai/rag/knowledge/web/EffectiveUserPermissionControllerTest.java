package com.yunjihai.rag.knowledge.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EffectiveUserPermissionControllerTest {
    @Test void rejectsUnmanagedSpaceBeforeLoadingTargetUser() {
        KnowledgeWriteAuthorization deny = (actor, space, document, action) -> { throw new AccessDeniedAsNotFoundException(); };
        var controller = new EffectiveUserPermissionController(null, deny);
        var auth = new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), "unused");
        assertThatThrownBy(() -> controller.get(auth, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
    }
}
