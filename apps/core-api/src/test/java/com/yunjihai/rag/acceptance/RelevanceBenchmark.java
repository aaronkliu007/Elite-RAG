package com.yunjihai.rag.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Test-only judgments and measurements; never used to authorize or filter production evidence. */
final class RelevanceBenchmark {
    enum Judgment { RELEVANT, IRRELEVANT, UNCERTAIN }

    record Document(String id, String text, Judgment judgment) {
        Document {
            requireText(id); requireText(text); Objects.requireNonNull(judgment);
        }
    }

    record Conflict(String first, String second, Judgment scope) {
        Conflict {
            requireText(first); requireText(second); Objects.requireNonNull(scope);
            if (first.equals(second)) throw new IllegalArgumentException("conflict sides must differ");
        }
    }

    record Sample(String id, String question, List<Document> documents, List<Conflict> conflicts) {
        Sample {
            requireText(id); requireText(question);
            documents = List.copyOf(documents); conflicts = List.copyOf(conflicts);
            var ids = new HashSet<String>();
            for (var doc : documents) if (!ids.add(doc.id())) throw new IllegalArgumentException("duplicate document id");
            if (documents.stream().noneMatch(doc -> doc.judgment() == Judgment.RELEVANT))
                throw new IllegalArgumentException("sample requires relevant evidence");
            Set<Set<String>> pairs = new HashSet<>();
            for (var conflict : conflicts) {
                if (!ids.contains(conflict.first()) || !ids.contains(conflict.second())
                        || !pairs.add(Set.of(conflict.first(), conflict.second())))
                    throw new IllegalArgumentException("invalid conflict pair");
            }
        }
    }

    record Dataset(String version, List<Sample> cases) {
        Dataset {
            requireText(version); cases = List.copyOf(cases);
            if (cases.isEmpty() || cases.stream().map(Sample::id).distinct().count() != cases.size())
                throw new IllegalArgumentException("dataset cases must be nonempty and unique");
        }
    }

    record Result(int relevantTotal, int relevantRetrieved, int irrelevantRetrieved,
                  int uncertainRetrieved, int unjudgedRetrieved,
                  int relevantPairsTotal, int relevantPairsComplete, int relevantPairsPartial,
                  int irrelevantPairsTotal, int irrelevantPairsComplete, int irrelevantPairsPartial,
                  int uncertainPairsTotal, int uncertainPairsComplete, int uncertainPairsPartial) { }

    static Dataset load() throws IOException {
        try (var input = RelevanceBenchmark.class.getResourceAsStream("/relevance-benchmark-v1.json")) {
            if (input == null) throw new IllegalStateException("relevance benchmark missing");
            return new ObjectMapper().readValue(input, Dataset.class);
        }
    }

    static Result measure(Sample sample, List<String> retrieved) {
        Objects.requireNonNull(sample); retrieved = List.copyOf(retrieved);
        Set<String> ids = Set.copyOf(retrieved);
        if (ids.size() != retrieved.size()) throw new IllegalArgumentException("duplicate retrieval id");
        Map<String, Judgment> labels = sample.documents().stream()
                .collect(Collectors.toUnmodifiableMap(Document::id, Document::judgment));
        int relevantTotal = (int) labels.values().stream().filter(value -> value == Judgment.RELEVANT).count();
        int relevant = 0, irrelevant = 0, uncertain = 0, unjudged = 0;
        for (String id : ids) {
            var label = labels.get(id);
            if (label == null) unjudged++;
            else switch (label) {
                case RELEVANT -> relevant++;
                case IRRELEVANT -> irrelevant++;
                case UNCERTAIN -> uncertain++;
            }
        }
        int rt = 0, rc = 0, rp = 0, it = 0, ic = 0, ip = 0, ut = 0, uc = 0, up = 0;
        for (var pair : sample.conflicts()) {
            int sides = (ids.contains(pair.first()) ? 1 : 0) + (ids.contains(pair.second()) ? 1 : 0);
            switch (pair.scope()) {
                case RELEVANT -> { rt++; if (sides == 2) rc++; else if (sides == 1) rp++; }
                case IRRELEVANT -> { it++; if (sides == 2) ic++; else if (sides == 1) ip++; }
                case UNCERTAIN -> { ut++; if (sides == 2) uc++; else if (sides == 1) up++; }
            }
        }
        return new Result(relevantTotal, relevant, irrelevant, uncertain, unjudged, rt, rc, rp, it, ic, ip, ut, uc, up);
    }

    /** Measures only screening-induced loss, not overall retrieval or answer quality. */
    record ScreeningAssessment(int removedRelevant, int removedIrrelevant, int removedUncertain,
                               int removedUnjudged, int harmedRelevantPairs, int harmedUncertainPairs,
                               int preexistingIncompletePairs, boolean preservesAvailableSafetyEvidence) { }

    static ScreeningAssessment assessScreening(Sample sample, List<String> candidates, List<String> retained) {
        Objects.requireNonNull(sample);
        candidates = List.copyOf(candidates);
        retained = List.copyOf(retained);
        Set<String> before = Set.copyOf(candidates);
        Set<String> after = Set.copyOf(retained);
        if (before.size() != candidates.size() || after.size() != retained.size())
            throw new IllegalArgumentException("duplicate candidate id");
        if (!before.containsAll(after)) throw new IllegalArgumentException("retained id not in candidates");
        Map<String, Judgment> labels = sample.documents().stream()
                .collect(Collectors.toUnmodifiableMap(Document::id, Document::judgment));
        int relevant = 0, irrelevant = 0, uncertain = 0, unjudged = 0;
        for (String id : before) {
            if (after.contains(id)) continue;
            var label = labels.get(id);
            if (label == null) unjudged++;
            else switch (label) {
                case RELEVANT -> relevant++;
                case IRRELEVANT -> irrelevant++;
                case UNCERTAIN -> uncertain++;
            }
        }
        int harmedRelevant = 0, harmedUncertain = 0, incomplete = 0;
        for (var pair : sample.conflicts()) {
            if (pair.scope() == Judgment.IRRELEVANT) continue;
            boolean completeBefore = before.contains(pair.first()) && before.contains(pair.second());
            if (!completeBefore) { incomplete++; continue; }
            if (!after.contains(pair.first()) || !after.contains(pair.second())) {
                if (pair.scope() == Judgment.RELEVANT) harmedRelevant++;
                else harmedUncertain++;
            }
        }
        return new ScreeningAssessment(relevant, irrelevant, uncertain, unjudged,
                harmedRelevant, harmedUncertain, incomplete,
                !before.isEmpty() && relevant == 0 && uncertain == 0 && unjudged == 0
                        && harmedRelevant == 0 && harmedUncertain == 0);
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("text required");
    }
}
