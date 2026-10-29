package com.yunjihai.rag.solution.feedback;

import java.time.Instant;
import java.util.UUID;

public record SolutionFeedback(UUID id, UUID solutionId, UUID finalizedVersionId,
        UUID sectionVersionId, UUID actorId, FeedbackReason reason, String clientKey, Instant createdAt) { }
