package com.yunjihai.rag.retrieval.selection;

import com.yunjihai.rag.retrieval.domain.ScoredCandidate;

import java.util.List;

@FunctionalInterface
public interface RerankerPort {

    List<RerankScore> rerank(String queryText, List<ScoredCandidate> candidates);
}
