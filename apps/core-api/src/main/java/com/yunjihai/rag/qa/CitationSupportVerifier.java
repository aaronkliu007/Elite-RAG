package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.domain.CandidateAnswer;
import com.yunjihai.rag.qa.domain.EvidenceBundle;

@FunctionalInterface
public interface CitationSupportVerifier {
    boolean isSupported(CandidateAnswer answer, EvidenceBundle evidence);
}
