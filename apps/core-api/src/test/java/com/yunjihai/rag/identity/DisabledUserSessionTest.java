package com.yunjihai.rag.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisabledUserSessionTest {

    @Test
    @DisplayName("撤销后签发的新会话有效而旧会话立即失效")
    void invalidatesOnlySessionsIssuedBeforeRevocation() {
        var sessions = new SessionRevocationService(new InMemorySessionVersionStore());
        var oldSession = sessions.issue("u-1");

        sessions.revokeAll("u-1");
        var newSession = sessions.issue("u-1");

        assertThatThrownBy(() -> sessions.assertActive(oldSession))
                .isInstanceOf(DisabledUserSessionException.class);
        sessions.assertActive(newSession);
    }
}
