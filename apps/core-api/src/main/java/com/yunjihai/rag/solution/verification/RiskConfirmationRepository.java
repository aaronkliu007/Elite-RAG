package com.yunjihai.rag.solution.verification;

import java.util.List;
import java.util.UUID;

public interface RiskConfirmationRepository {

    List<RiskConfirmation> findBySolutionId(UUID solutionId);

    RiskConfirmation insertIfAbsent(RiskConfirmation confirmation);
}
