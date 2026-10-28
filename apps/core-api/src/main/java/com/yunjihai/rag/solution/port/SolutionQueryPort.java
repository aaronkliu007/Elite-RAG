package com.yunjihai.rag.solution.port;

import com.yunjihai.rag.solution.domain.Solution;
import java.util.List;
import java.util.UUID;

public interface SolutionQueryPort {
    List<Solution> listBySpace(UUID spaceId, int page, int pageSize);
}
