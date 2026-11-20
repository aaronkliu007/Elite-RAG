package com.yunjihai.rag.knowledge.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.PermissionGrantQuery;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionOverviewControllerTest {
    @Test void requiresManagementAuthorizationBeforeQuerying() {
        var called = new AtomicBoolean(false);
        PermissionGrantQuery query = (space, offset, limit) -> { called.set(true); return List.of(); };
        KnowledgeWriteAuthorization deny = (actor, space, document, action) -> { throw new AccessDeniedAsNotFoundException(); };
        var controller = new PermissionOverviewController(query, deny);
        var auth = new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), "unused");
        assertThatThrownBy(() -> controller.list(auth, UUID.randomUUID(), 1, 100))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThat(called).isFalse();
    }
}
