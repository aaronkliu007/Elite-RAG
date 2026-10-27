package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.access.role.SpaceRole;
import com.yunjihai.rag.qa.domain.QaStatus;
import com.yunjihai.rag.qa.web.QaDtos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedQaSecurityIntegrationTest {
    private static GroundedQaAcceptanceEnvironment environment;

    @BeforeAll
    static void start() {
        environment = new GroundedQaAcceptanceEnvironment();
    }

    @AfterAll
    static void stop() {
        if (environment != null) environment.close();
    }

    @Test
    void refusesModelResultWhenEveryPersistedEvidenceSpaceIsRevokedDuringGeneration() throws Exception {
        var scenario = environment.seed("late-revocation");
        environment.gateway.blockNextChat();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> environment.qa.ask(
                    environment.authentication(scenario.reader()),
                    "qa-late-revocation-" + scenario.suffix(),
                    new QaDtos.AskQuestionRequest("苍穹验收知识如何执行？")));

            assertThat(environment.gateway.awaitBlockedChat()).isTrue();
            scenario.allowedSpaces().forEach(spaceId -> environment.roles.revokeSpaceRole(
                    scenario.admin(), spaceId, scenario.reader(), SpaceRole.READER, Instant.now()));

            environment.gateway.releaseBlockedChat();
            var response = result.get(10, TimeUnit.SECONDS);
            assertThat(response.status()).isEqualTo(QaStatus.REFUSED);
            assertThat(response.refusal()).isEqualTo("NO_AUTHORIZED_SPACE");
            assertThat(response.answer()).isNull();
            assertThat(response.citations()).isEmpty();
            var evidenceSpaces = environment.jdbc.sql("""
                    SELECT DISTINCT space_id FROM qa_evidence_snapshot
                    WHERE request_id=:requestId ORDER BY space_id
                    """).param("requestId", response.requestId()).query(UUID.class).list();
            assertThat(evidenceSpaces).containsExactlyInAnyOrderElementsOf(scenario.allowedSpaces());
        } finally {
            environment.gateway.releaseBlockedChat();
        }
    }

    @Test
    void blocksSensitiveProjectionAndRefusesBeforeAnyChatRequestLeavesTheApplication() {
        var scenario = environment.seedUnsafe("sensitive-evidence");
        int chatCallsBefore = environment.gateway.chatCalls();

        var response = environment.qa.ask(
                environment.authentication(scenario.reader()),
                "qa-sensitive-" + scenario.suffix(),
                new QaDtos.AskQuestionRequest("苍穹验收知识如何执行？"));

        assertThat(response.status()).isEqualTo(QaStatus.REFUSED);
        assertThat(response.refusal()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.answer()).isNull();
        assertThat(environment.gateway.chatCalls()).isEqualTo(chatCallsBefore);
    }
}
