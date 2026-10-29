package com.yunjihai.rag.solution.feedback;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeedbackStore {
    Optional<SolutionFeedback> findByKey(UUID actorId, UUID solutionId, String clientKey);
    SolutionFeedback insertIfAbsent(SolutionFeedback feedback);
    List<SolutionFeedback> listByActorAndVersion(UUID actorId, UUID finalizedVersionId);
}
