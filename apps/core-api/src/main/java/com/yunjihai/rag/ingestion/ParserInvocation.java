package com.yunjihai.rag.ingestion;

@FunctionalInterface
public interface ParserInvocation {

    CanonicalDocumentV1 parse(IngestionLease lease);
}
