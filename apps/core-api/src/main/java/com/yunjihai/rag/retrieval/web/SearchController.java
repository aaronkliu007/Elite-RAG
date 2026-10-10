package com.yunjihai.rag.retrieval.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.common.UuidV7;
import com.yunjihai.rag.knowledge.KnowledgeSpaceReadAuthorization;
import com.yunjihai.rag.retrieval.RetrievalService;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@RestController
@ConditionalOnBean({RetrievalService.class, KnowledgeSpaceReadAuthorization.class})
public class SearchController {
    private final RetrievalService retrieval;
    private final KnowledgeSpaceReadAuthorization spaces;
    private final Clock clock;

    public SearchController(
            RetrievalService retrieval,
            KnowledgeSpaceReadAuthorization spaces,
            Clock clock) {
        this.retrieval = Objects.requireNonNull(retrieval);
        this.spaces = Objects.requireNonNull(spaces);
        this.clock = Objects.requireNonNull(clock);
    }

    @PostMapping("/api/v1/search")
    public SearchResponse search(
            Authentication authentication,
            @RequestBody SearchRequest request) {
        UUID userId = userId(authentication);
        if (request == null
                || request.spaceId() == null
                || request.queryText() == null
                || request.requestedEvidenceCount() == null) {
            throw new IllegalArgumentException("检索请求参数不完整");
        }
        var at = clock.instant();
        spaces.requireRead(userId, request.spaceId(), at);
        var query = new RetrievalQuery(
                UuidV7.generate(at),
                userId,
                Set.of(request.spaceId()),
                request.queryText(),
                request.metadataFilter() == null ? Map.of() : request.metadataFilter(),
                request.requestedEvidenceCount());
        return SearchResponse.from(retrieval.retrieve(query));
    }

    private static UUID userId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AccessDeniedAsNotFoundException();
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalid) {
            throw new AccessDeniedAsNotFoundException();
        }
    }
}
