package com.yunjihai.rag.retrieval;

import com.yunjihai.rag.retrieval.domain.EvidencePack;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;

@FunctionalInterface
public interface RetrievalService {

    EvidencePack retrieve(RetrievalQuery query);
}
