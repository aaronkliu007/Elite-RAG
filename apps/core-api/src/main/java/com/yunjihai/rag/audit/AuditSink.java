package com.yunjihai.rag.audit;

@FunctionalInterface
public interface AuditSink {

    AuditEvent record(AuditCommand command);

    static AuditSink noop() {
        return command -> null;
    }
}
