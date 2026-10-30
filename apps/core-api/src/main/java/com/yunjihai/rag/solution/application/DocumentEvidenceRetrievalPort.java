package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.EvidencePack;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import java.util.Set;

/** Implementations must constrain recall and all outbound reranking to these exact versions. */
@FunctionalInterface
public interface DocumentEvidenceRetrievalPort {
    EvidencePack retrieve(RetrievalQuery query, Set<DocumentVersionRef> allowedVersions);
}
