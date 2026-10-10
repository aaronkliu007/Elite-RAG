package com.yunjihai.rag.qa;

import java.util.Objects;
import java.util.UUID;

public record AskQuestionCommand(UUID requestId, UUID actorId, String question) {
    public AskQuestionCommand {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(question, "question must not be null");
        if (question.isBlank()) throw new IllegalArgumentException("question must not be blank");
    }
}
