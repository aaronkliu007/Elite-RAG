package com.yunjihai.rag.solution.verification;

import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.solution.domain.Claim;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class SectionVerificationService {

    private static final Pattern QUANTIFIED_VALUE = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:万元|元|天|日|个月|月|年|%|秒|毫秒|GB|MB|TPS|QPS|名|人|台)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final CitationVerificationPort semanticPort;

    public SectionVerificationService(CitationVerificationPort semanticPort) {
        this.semanticPort = Objects.requireNonNull(semanticPort, "semanticPort");
    }

    public SolutionRiskReport verify(List<Claim> claims, List<Evidence> evidence, Instant deadline) {
        var claimList = List.copyOf(Objects.requireNonNull(claims, "claims"));
        var evidenceList = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        Objects.requireNonNull(deadline, "deadline");
        var evidenceById = validateInputs(claimList, evidenceList);

        var localResults = new LinkedHashMap<UUID, ClaimVerification>();
        var semanticInputs = new ArrayList<ClaimEvidenceInput>();
        for (var claim : claimList) {
            if (!claim.factual()) {
                localResults.put(claim.claimId(), new ClaimVerification(
                        claim.claimId(), CitationVerdict.SUPPORTED, "NON_FACTUAL"));
                continue;
            }
            var citedEvidence = claim.evidenceIds().stream()
                    .map(evidenceById::get)
                    .toList();
            if (hasContradictingQuantifiedValue(claim.text(), citedEvidence)) {
                localResults.put(claim.claimId(), new ClaimVerification(
                        claim.claimId(), CitationVerdict.UNSUPPORTED, "QUANTIFIED_VALUE_MISMATCH"));
                continue;
            }
            semanticInputs.add(new ClaimEvidenceInput(claim.claimId(), claim.text(), citedEvidence));
        }

        Map<UUID, ClaimVerification> semanticResults = Map.of();
        if (!semanticInputs.isEmpty()) {
            semanticResults = verifySemantically(semanticInputs, deadline);
        }

        var results = new ArrayList<ClaimVerification>(claimList.size());
        for (var claim : claimList) {
            var verification = localResults.containsKey(claim.claimId())
                    ? localResults.get(claim.claimId())
                    : semanticResults.get(claim.claimId());
            results.add(applyRiskRule(claim, verification));
        }
        return new SolutionRiskReport(results);
    }

    private Map<UUID, ClaimVerification> verifySemantically(
            List<ClaimEvidenceInput> inputs,
            Instant deadline) {
        try {
            var expectedIds = inputs.stream()
                    .map(ClaimEvidenceInput::claimId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            var returned = semanticPort.verify(List.copyOf(inputs), deadline);
            if (returned == null || returned.size() != expectedIds.size()) {
                return unavailableResults(inputs);
            }
            var byId = new LinkedHashMap<UUID, ClaimVerification>();
            for (var result : returned) {
                if (result == null
                        || !expectedIds.contains(result.claimId())
                        || byId.putIfAbsent(result.claimId(), result) != null) {
                    return unavailableResults(inputs);
                }
            }
            if (!byId.keySet().equals(expectedIds)) {
                return unavailableResults(inputs);
            }
            return Map.copyOf(byId);
        } catch (RuntimeException unavailable) {
            return unavailableResults(inputs);
        }
    }

    private static Map<UUID, ClaimVerification> unavailableResults(List<ClaimEvidenceInput> inputs) {
        var results = new LinkedHashMap<UUID, ClaimVerification>();
        inputs.forEach(input -> results.put(input.claimId(), new ClaimVerification(
                input.claimId(),
                CitationVerdict.HUMAN_CONFIRMATION_REQUIRED,
                "SEMANTIC_UNAVAILABLE")));
        return Map.copyOf(results);
    }

    private static ClaimVerification applyRiskRule(Claim claim, ClaimVerification verification) {
        if (verification.verdict() == CitationVerdict.SUPPORTED && !claim.riskTypes().isEmpty()) {
            return new ClaimVerification(
                    claim.claimId(),
                    CitationVerdict.HUMAN_CONFIRMATION_REQUIRED,
                    "HIGH_RISK_CONFIRMATION_REQUIRED");
        }
        return verification;
    }

    private static Map<UUID, Evidence> validateInputs(List<Claim> claims, List<Evidence> evidence) {
        var evidenceById = new HashMap<UUID, Evidence>();
        for (var item : evidence) {
            if (evidenceById.putIfAbsent(item.evidenceId(), item) != null) {
                throw new IllegalArgumentException("duplicate evidence id");
            }
        }
        var claimIds = new HashSet<UUID>();
        for (var claim : claims) {
            if (!claimIds.add(claim.claimId())) {
                throw new IllegalArgumentException("duplicate claim id");
            }
            if (claim.factual() && claim.evidenceIds().isEmpty()) {
                throw new IllegalArgumentException("factual claim requires evidence");
            }
            if (!evidenceById.keySet().containsAll(claim.evidenceIds())) {
                throw new IllegalArgumentException("claim references evidence outside whitelist");
            }
        }
        return Map.copyOf(evidenceById);
    }

    private static boolean hasContradictingQuantifiedValue(String statement, List<Evidence> evidence) {
        var claimValues = quantifiedValues(statement);
        if (claimValues.isEmpty()) {
            return false;
        }
        var evidenceValues = new LinkedHashSet<String>();
        evidence.forEach(item -> evidenceValues.addAll(quantifiedValues(item.excerpt())));
        return !evidenceValues.containsAll(claimValues);
    }

    private static Set<String> quantifiedValues(String text) {
        var normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        var matcher = QUANTIFIED_VALUE.matcher(normalized);
        var values = new LinkedHashSet<String>();
        while (matcher.find()) {
            values.add(matcher.group().replaceAll("\\s+", "").toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(values);
    }
}
