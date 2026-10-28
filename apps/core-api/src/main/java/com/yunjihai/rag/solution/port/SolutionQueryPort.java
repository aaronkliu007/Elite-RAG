package com.yunjihai.rag.solution.port;

import com.yunjihai.rag.solution.domain.SolutionPhase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SolutionQueryPort {
    List<SolutionListItem> listBySpace(UUID spaceId, int page, int pageSize);

    record SolutionListItem(UUID id, UUID spaceId, String name, SolutionPhase phase,
            long revision, Instant createdAt, Instant updatedAt) { }
}
