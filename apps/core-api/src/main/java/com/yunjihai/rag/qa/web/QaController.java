package com.yunjihai.rag.qa.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.api.idempotency.*;
import com.yunjihai.rag.common.UuidV7;
import com.yunjihai.rag.qa.*;
import com.yunjihai.rag.qa.domain.QaQuestion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/qa/questions")
@Profile("local")
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${rag.qa.hmac-key:}')")
public class QaController {
    private final GroundedQaService qa;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    private final Clock clock;

    public QaController(GroundedQaService qa,IdempotencyService idempotency,ObjectMapper json,Clock clock) {
        this.qa=Objects.requireNonNull(qa);
        this.idempotency=Objects.requireNonNull(idempotency);
        this.json=Objects.requireNonNull(json);
        this.clock=Objects.requireNonNull(clock);
    }

    @PostMapping
    public QaDtos.QuestionResponse ask(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody QaDtos.AskQuestionRequest request) {
        UUID actorId=userId(authentication);
        if(request==null)throw new IllegalArgumentException("request body is required");
        QaQuestion.normalize(request.question());
        String hash=IdempotencyService.sha256(json.writeValueAsBytes(request));
        return idempotency.execute(
                new IdempotencyScope(actorId,"qa.question.ask",idempotencyKey),
                hash,QaDtos.QuestionResponse.class,()->{
                    UUID requestId=UuidV7.generate(clock.instant());
                    var outcome=qa.ask(new AskQuestionCommand(requestId,actorId,request.question()));
                    Instant completedAt=qa.completedAt(requestId,actorId).orElseThrow(
                            ()->new IllegalStateException("QA_TERMINAL_RESULT_NOT_PERSISTED"));
                    return QaDtos.QuestionResponse.from(outcome,completedAt);
                });
    }

    @GetMapping("/{requestId}")
    public QaDtos.QuestionResponse get(Authentication authentication,@PathVariable UUID requestId) {
        UUID actorId=userId(authentication);
        var outcome=qa.find(requestId,actorId).orElseThrow(AccessDeniedAsNotFoundException::new);
        Instant completedAt=qa.completedAt(requestId,actorId).orElseThrow(
                AccessDeniedAsNotFoundException::new);
        return QaDtos.QuestionResponse.from(outcome,completedAt);
    }

    static UUID userId(Authentication authentication) {
        if(authentication==null||authentication.getName()==null)throw new AccessDeniedAsNotFoundException();
        try{return UUID.fromString(authentication.getName());}
        catch(IllegalArgumentException invalid){throw new AccessDeniedAsNotFoundException();}
    }
}
