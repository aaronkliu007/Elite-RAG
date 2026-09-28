package com.yunjihai.rag.retrieval.search;

import com.yunjihai.rag.retrieval.domain.Candidate;
import com.yunjihai.rag.retrieval.domain.RetrievalFilter;

import java.util.List;

@FunctionalInterface
public interface VectorRetriever {

    List<Candidate> retrieve(String queryText, RetrievalFilter filter, int limit);
}
