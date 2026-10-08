package com.yunjihai.rag.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisabledUserSessionTest {

    @Test
    @DisplayName("撤销后签发的新会话有效而旧会话立即失效")
    void invalidatesOnlySessionsIssuedBeforeRevocation() {
        UUID userId = UUID.randomUUID();
        var sessions = new SessionRevocationService(new InMemorySessionVersionStore());
        var oldSession = sessions.issue(userId);

        sessions.revokeAll(userId);
        var newSession = sessions.issue(userId);

        assertThatThrownBy(() -> sessions.assertActive(oldSession))
                .isInstanceOf(DisabledUserSessionException.class);
        sessions.assertActive(newSession);
    }
}
