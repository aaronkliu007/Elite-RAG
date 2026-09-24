package com.yunjihai.rag.audit;

@FunctionalInterface
public interface AuditEventStore {

    void append(AuditEvent event);
}
