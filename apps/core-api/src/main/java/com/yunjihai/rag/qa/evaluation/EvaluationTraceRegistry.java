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

    public void recordRetrieval(UUID requestId, Collection<EvaluationCase.EvidenceRef> refs) {
        var trace = active.get(requestId);
        if (trace != null) trace.setRetrieval(refs);
    }

    public void recordSanitizedQuestion(UUID requestId, String question) {
        var trace = active.get(requestId);
        if (trace == null) return;
        try {
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(question.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            trace.setQuestionDigest(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public Trace consume(UUID requestId) {
        var trace = active.remove(requestId);
        return trace == null ? Trace.empty() : trace.snapshot();
    }

    public record Trace(
            boolean retrievalObserved,
            Set<EvaluationCase.EvidenceRef> retrievedTop10,
            Set<EvaluationCase.EvidenceRef> modelRequest,
            Set<EvaluationCase.EvidenceRef> modelResponse,
            String sanitizedQuestionDigest) {
        public Trace {
            retrievedTop10 = Set.copyOf(retrievedTop10);
            modelRequest = Set.copyOf(modelRequest);
            modelResponse = Set.copyOf(modelResponse);
        }
        static Trace empty() { return new Trace(false, Set.of(), Set.of(), Set.of(), null); }
    }

    private static final class MutableTrace {
        private final Set<EvaluationCase.EvidenceRef> request = new LinkedHashSet<>();
        private final Set<EvaluationCase.EvidenceRef> response = new LinkedHashSet<>();
        private final Set<EvaluationCase.EvidenceRef> retrieval = new LinkedHashSet<>();
        private boolean retrievalObserved;
        private String questionDigest;
        synchronized void setRetrieval(Collection<EvaluationCase.EvidenceRef> refs) {
            retrieval.clear(); retrieval.addAll(refs); retrievalObserved = true;
        }
        synchronized void addRequest(Collection<EvaluationCase.EvidenceRef> refs) { request.addAll(refs); }
        synchronized void addResponse(Collection<EvaluationCase.EvidenceRef> refs) { response.addAll(refs); }
        synchronized void setQuestionDigest(String digest) { questionDigest = digest; }
        synchronized Trace snapshot() { return new Trace(retrievalObserved, retrieval, request, response, questionDigest); }
    }
}
