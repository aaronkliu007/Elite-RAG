package com.yunjihai.rag.qa.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.qa.domain.QaOutcome;
import com.yunjihai.rag.qa.domain.QaStatus;
import com.yunjihai.rag.qa.domain.RefusalReason;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static com.yunjihai.rag.qa.web.QaTestHarness.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QaControllerTest {
    @Test
    void mapsTerminalResultsWithoutExposingQuotesOrEvidenceBodies() {
        var answered=new QaTestHarness(Mode.ANSWERED);
        var answer=answered.controller.ask(auth(ACTOR),"answer-key",
                new QaDtos.AskQuestionRequest("制度何时生效？"));
        assertThat(answer.status()).isEqualTo(QaStatus.ANSWERED);
        assertThat(answer.answer()).isEqualTo("制度已经生效。");
        assertThat(answer.refusal()).isNull();
        assertThat(answer.degraded()).isFalse();
        assertThat(answer.completedAt()).isEqualTo(NOW);
        assertThat(answer.citations()).singleElement().satisfies(citation->{
            assertThat(citation.text()).isEqualTo("制度已经生效。");
            assertThat(citation.evidenceIds()).containsExactly(id(100));
        });
        assertThat(new ObjectMapper().writeValueAsString(answer))
                .doesNotContain("证据正文","exactQuote","quote");

        var refused=new QaTestHarness(Mode.REFUSED).controller.ask(auth(ACTOR),"refuse-key",
                new QaDtos.AskQuestionRequest("没有依据的问题"));
        assertThat(refused.status()).isEqualTo(QaStatus.REFUSED);
        assertThat(refused.answer()).isNull();
        assertThat(refused.refusal()).isEqualTo(RefusalReason.NO_AUTHORIZED_SPACE.name());
        assertThat(refused.citations()).isEmpty();

        var failed=new QaTestHarness(Mode.FAILED).controller.ask(auth(ACTOR),"fail-key",
                new QaDtos.AskQuestionRequest("有效问题"));
        assertThat(failed.status()).isEqualTo(QaStatus.FAILED);
        assertThat(failed.answer()).isNull();
        assertThat(failed.refusal()).isNull();
        assertThat(failed.citations()).isEmpty();

        var degradedFailure=QaDtos.QuestionResponse.from(
                new QaOutcome.Failed(id(5),"MODEL_UNAVAILABLE",true),NOW);
        assertThat(degradedFailure.degraded()).isTrue();
    }

    @Test
    void restoresOnlyOwnersResultAndUsesSessionIdentity() {
        var fixture=new QaTestHarness(Mode.ANSWERED);
        var created=fixture.controller.ask(auth(ACTOR),"owner-key",
                new QaDtos.AskQuestionRequest("制度何时生效？"));
        assertThat(fixture.controller.get(auth(ACTOR),created.requestId())).isEqualTo(created);
        assertThat(fixture.lastActor).hasValue(ACTOR);
        assertThatThrownBy(()->fixture.controller.get(auth(id(2)),created.requestId()))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(()->fixture.controller.get(auth(ACTOR),id(999)))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    @Test
    void rejectsNullRequestBeforeStartingQa() {
        var fixture=new QaTestHarness(Mode.ANSWERED);
        assertThatThrownBy(()->fixture.controller.ask(auth(ACTOR),"null",null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->fixture.controller.ask(auth(ACTOR),"blank",
                new QaDtos.AskQuestionRequest("   ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.modelCalls).hasValue(0);
    }
}
