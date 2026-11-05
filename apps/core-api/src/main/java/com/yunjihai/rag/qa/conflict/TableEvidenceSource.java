package com.yunjihai.rag.qa.conflict;

import com.yunjihai.rag.ingestion.CanonicalTableCell;
import com.yunjihai.rag.retrieval.domain.Evidence;
import java.util.List;

@FunctionalInterface
public interface TableEvidenceSource {
    List<CanonicalTableCell> load(Evidence evidence);
}
