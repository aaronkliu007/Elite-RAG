package com.yunjihai.rag.retrieval.selection;

import com.yunjihai.rag.retrieval.domain.RetrievalPolicy;
import com.yunjihai.rag.retrieval.domain.ScoredCandidate;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class EvidenceSelectionService {

    private final RetrievalPolicy policy;

    public EvidenceSelectionService(RetrievalPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    public List<ScoredCandidate> select(
            List<ScoredCandidate> authorizedCandidates,
            int limit) {
        Objects.requireNonNull(authorizedCandidates, "authorizedCandidates must not be null");
        if (limit < policy.minEvidenceCount() || limit > policy.maxEvidenceCount()) {
            throw new IllegalArgumentException("evidence limit is outside the configured range");
        }
        var input = List.copyOf(authorizedCandidates);
        var selected = new ArrayList<SelectedCandidate>();
        Map<UUID, Integer> countsByDocument = new HashMap<>();
        for (var candidate : input) {
            if (selected.size() >= limit) {
                break;
            }
            var normalized = normalize(candidate.candidate().text());
            if (isNearDuplicate(normalized, selected)) {
                continue;
            }
            var documentId = candidate.candidate().documentId();
            int documentCount = countsByDocument.getOrDefault(documentId, 0);
            if (documentCount >= policy.maxPerDocument()) {
                continue;
            }
            selected.add(new SelectedCandidate(candidate, normalized, trigrams(normalized)));
            countsByDocument.put(documentId, documentCount + 1);
        }
        return selected.stream().map(SelectedCandidate::candidate).toList();
    }

    private boolean isNearDuplicate(String normalized, List<SelectedCandidate> selected) {
        var codePoints = normalized.codePoints().toArray();
        if (codePoints.length < 3) {
            return selected.stream().anyMatch(existing -> existing.normalized().equals(normalized));
        }
        var grams = trigrams(normalized);
        return selected.stream().anyMatch(existing ->
                similarity(grams, existing.grams()) >= policy.nearDuplicateThreshold());
    }

    private static String normalize(String text) {
        var normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        var result = new StringBuilder(normalized.length());
        boolean previousWasWhitespace = true;
        var codePoints = normalized.codePoints().toArray();
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            if (isPunctuation(codePoint)
                    && !isSemanticNumericPunctuation(codePoints, index)) {
                continue;
            }
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (!previousWasWhitespace) {
                    result.append(' ');
                    previousWasWhitespace = true;
                }
            } else {
                result.appendCodePoint(codePoint);
                previousWasWhitespace = false;
            }
        }
        if (!result.isEmpty() && result.charAt(result.length() - 1) == ' ') {
            result.setLength(result.length() - 1);
        }
        return result.toString();
    }

    private static boolean isSemanticNumericPunctuation(int[] codePoints, int index) {
        boolean previousIsDigit = index > 0 && Character.isDigit(codePoints[index - 1]);
        boolean nextIsDigit = index + 1 < codePoints.length
                && Character.isDigit(codePoints[index + 1]);
        if (previousIsDigit && nextIsDigit) {
            return true;
        }
        int codePoint = codePoints[index];
        boolean numericSign = codePoint == '-' || codePoint == '\u2010'
                || codePoint == '\u2011' || codePoint == '\u2212';
        boolean startsNumericToken = index == 0
                || Character.isWhitespace(codePoints[index - 1])
                || Character.isSpaceChar(codePoints[index - 1])
                || isPunctuation(codePoints[index - 1]);
        return numericSign && nextIsDigit && startsNumericToken;
    }

    private static boolean isPunctuation(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION,
                    Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    private static Set<String> trigrams(String normalized) {
        var codePoints = normalized.codePoints().toArray();
        var grams = new HashSet<String>();
        for (int index = 0; index + 2 < codePoints.length; index++) {
            grams.add(new String(codePoints, index, 3));
        }
        return Set.copyOf(grams);
    }

    private static double similarity(Set<String> first, Set<String> second) {
        if (first.isEmpty() || second.isEmpty()) {
            return first.equals(second) ? 1 : 0;
        }
        int intersection = 0;
        for (var gram : first) {
            if (second.contains(gram)) {
                intersection++;
            }
        }
        int union = first.size() + second.size() - intersection;
        return (double) intersection / union;
    }

    private record SelectedCandidate(
            ScoredCandidate candidate,
            String normalized,
            Set<String> grams) {
    }
}
