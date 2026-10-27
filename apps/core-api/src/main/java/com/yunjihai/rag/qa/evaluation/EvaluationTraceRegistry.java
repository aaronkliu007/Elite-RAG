package com.yunjihai.rag.qa.evaluation;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluation-only capture of safe evidence identities. It never stores question, evidence, prompt,
 * answer, or model response text and ignores ordinary production requests unless explicitly armed.
 */
public final class EvaluationTraceRegistry {
    private final ConcurrentHashMap<UUID, MutableTrace> active = new ConcurrentHashMap<>();

    public void begin(UUID requestId) {
        if (active.size() >= 100) throw new IllegalStateException("too many active evaluation traces");
        if (active.putIfAbsent(requestId, new MutableTrace()) != null) {
            throw new IllegalStateException("evaluation trace already exists");
        }
    }

    public void recordRequest(UUID requestId, Collection<EvaluationCase.EvidenceRef> refs) {
        var trace = active.get(requestId);
        if (trace != null) trace.addRequest(refs);
    }

    public void recordResponse(UUID requestId, Collection<EvaluationCase.EvidenceRef> refs) {
        var trace = active.get(requestId);
        if (trace != null) trace.addResponse(refs);
    }

    public Trace consume(UUID requestId) {
        var trace = active.remove(requestId);
        return trace == null ? Trace.empty() : trace.snapshot();
    }

    public record Trace(
            Set<EvaluationCase.EvidenceRef> modelRequest,
            Set<EvaluationCase.EvidenceRef> modelResponse) {
        public Trace {
            modelRequest = Set.copyOf(modelRequest);
            modelResponse = Set.copyOf(modelResponse);
        }
        static Trace empty() { return new Trace(Set.of(), Set.of()); }
    }

    private static final class MutableTrace {
        private final Set<EvaluationCase.EvidenceRef> request = new LinkedHashSet<>();
        private final Set<EvaluationCase.EvidenceRef> response = new LinkedHashSet<>();
        synchronized void addRequest(Collection<EvaluationCase.EvidenceRef> refs) { request.addAll(refs); }
        synchronized void addResponse(Collection<EvaluationCase.EvidenceRef> refs) { response.addAll(refs); }
        synchronized Trace snapshot() { return new Trace(request, response); }
    }
}
