package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.qa.domain.QaStatus;
import com.yunjihai.rag.qa.web.QaDtos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedQaModelGatewayIntegrationTest {
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
    void rateLimitRetriesOnceThenFailsClosedWithoutAnswer() {
        var scenario = environment.seed("rate-limit");
        environment.gateway.rateLimit();
        int chatCallsBefore = environment.gateway.chatCalls();

        var response = ask(scenario, "rate-limit");

        assertThat(response.status()).isEqualTo(QaStatus.FAILED);
        assertThat(response.answer()).isNull();
        assertThat(response.citations()).isEmpty();
        assertThat(environment.gateway.chatCalls() - chatCallsBefore).isEqualTo(4);
    }

    @Test
    void responseTimeoutRetriesOnceThenFailsClosedWithoutAnswer() {
        var scenario = environment.seed("timeout");
        environment.gateway.timeout();
        int chatCallsBefore = environment.gateway.chatCalls();

        var response = ask(scenario, "timeout");

        assertThat(response.status()).isEqualTo(QaStatus.FAILED);
        assertThat(response.answer()).isNull();
        assertThat(response.citations()).isEmpty();
        assertThat(environment.gateway.chatCalls() - chatCallsBefore).isEqualTo(4);
    }

    @Test
    void unknownCitationIsStrictlyRefusedAsInvalidModelOutput() {
        var scenario = environment.seed("invalid-citation");
        environment.gateway.invalidCitation();
        int chatCallsBefore = environment.gateway.chatCalls();

        var response = ask(scenario, "invalid-citation");

        assertThat(response.status()).isEqualTo(QaStatus.REFUSED);
        assertThat(response.refusal()).isEqualTo("INVALID_MODEL_OUTPUT");
        assertThat(response.answer()).isNull();
        assertThat(response.citations()).isEmpty();
        assertThat(environment.gateway.chatCalls() - chatCallsBefore).isEqualTo(1);
    }

    private static QaDtos.QuestionResponse ask(
            GroundedQaAcceptanceEnvironment.Scenario scenario, String label) {
        return environment.qa.ask(
                environment.authentication(scenario.reader()),
                "qa-model-" + label + '-' + scenario.suffix(),
                new QaDtos.AskQuestionRequest("苍穹验收知识如何执行？"));
    }
}
