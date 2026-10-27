package com.yunjihai.rag.retrieval;

/** Raised before an external embedding or reranking call when text cannot be sent safely. */
public final class RetrievalOutboundPolicyException extends RuntimeException {
    public RetrievalOutboundPolicyException() {
        super("retrieval text is not approved for outbound processing");
    }
}
