package com.yunjihai.rag.qa.web;

import com.yunjihai.rag.api.idempotency.IdempotencyConflictException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static com.yunjihai.rag.qa.web.QaTestHarness.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QaIdempotencyIntegrationTest {
    @Test
    void sameKeyAndPayloadExecutesQaOnceAndPayloadMismatchConflicts() {
        var fixture=new QaTestHarness(Mode.ANSWERED);
        var request=new QaDtos.AskQuestionRequest("制度何时生效？");
        var first=fixture.controller.ask(auth(ACTOR),"same-key",request);
        var replay=fixture.controller.ask(auth(ACTOR),"same-key",request);
        assertThat(replay).isEqualTo(first);
        assertThat(fixture.modelCalls).hasValue(1);
        assertThat(fixture.rateLimitAcquisitions).hasValue(1);

        assertThatThrownBy(()->fixture.controller.ask(auth(ACTOR),"same-key",
                new QaDtos.AskQuestionRequest("另一个问题")))
                .isInstanceOf(IdempotencyConflictException.class)
                .extracting("reason")
                .isEqualTo(IdempotencyConflictException.Reason.REQUEST_MISMATCH);
        assertThat(fixture.rateLimitAcquisitions).hasValue(1);
    }

    @Test
    void concurrentSameKeyInvokesQaOnlyOnce() throws Exception {
        var fixture=new QaTestHarness(Mode.ANSWERED,true);
        var request=new QaDtos.AskQuestionRequest("制度何时生效？");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->fixture.controller.ask(auth(ACTOR),"concurrent",request));
            fixture.modelEntered.await();
            try {
                assertThatThrownBy(()->fixture.controller.ask(auth(ACTOR),"concurrent",request))
                        .isInstanceOf(IdempotencyConflictException.class)
                        .extracting("reason")
                        .isEqualTo(IdempotencyConflictException.Reason.IN_PROGRESS);
            } finally {
                fixture.releaseModel.countDown();
            }
            assertThat(first.get().status()).isEqualTo(com.yunjihai.rag.qa.domain.QaStatus.ANSWERED);
        }
        assertThat(fixture.modelCalls).hasValue(1);
    }
}
