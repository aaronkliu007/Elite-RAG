package com.yunjihai.rag.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.solution.domain.Claim;
import com.yunjihai.rag.solution.domain.RiskType;
import com.yunjihai.rag.solution.feedback.FeedbackReason;
import com.yunjihai.rag.solution.verification.CitationVerdict;
import com.yunjihai.rag.solution.verification.ClaimVerification;
import com.yunjihai.rag.solution.verification.SectionVerificationService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract for the synthetic generation fixture; real pilot quality requires separate measured cases. */
class SolutionWorkbenchAcceptanceTest {
    private static final Path GOLDEN = Path.of("..", "..", "evaluation", "generation", "golden-v1.jsonl");

    @Test void syntheticGoldenCasesCoverFormatsRefusalPermissionsAndHumanRisk() throws Exception {
        assertThat(FeedbackReason.values()).extracting(Enum::name).containsExactly(
                "ACCEPTED", "FACT_ERROR", "MISSING_EVIDENCE", "IRRELEVANT", "OUTDATED",
                "STYLE", "DUPLICATE", "SCOPE_MISMATCH", "OTHER");
        var mapper = new ObjectMapper();
        var ids = new HashSet<String>();
        var formats = new HashSet<String>();
        var decisions = new HashSet<String>();
        var scenarios = new HashSet<String>();
        var lines = Files.readAllLines(GOLDEN).stream().filter(line -> !line.isBlank()).toList();
        for (var line : lines) {
            var item = mapper.readTree(line);
            assertThat(item.path("synthetic").asBoolean()).isTrue();
            assertThat(item.path("requirement").asText()).contains("虚构");
            assertThat(item.path("evidence").isArray()).isTrue();
            assertThat(item.path("expectedDecision").asText()).isIn("GENERATE", "REFUSE", "REQUIRE_HUMAN_REVIEW");
            assertThat(ids.add(item.path("id").asText())).isTrue();
            formats.add(item.path("sourceFormat").asText());
            decisions.add(item.path("expectedDecision").asText());
            scenarios.add(item.path("scenario").asText());
        }
        assertThat(lines).hasSizeGreaterThanOrEqualTo(12);
        assertThat(formats).containsAll(Set.of("WORD", "EXCEL", "PDF", "PPT", "MARKDOWN", "PNG_OCR"));
        assertThat(decisions).contains("REFUSE", "REQUIRE_HUMAN_REVIEW", "GENERATE");
        assertThat(scenarios).contains("PERMISSION_TRAP", "MISSING_EVIDENCE", "CONFLICT", "PROMPT_INJECTION");
    }

    @Test void selectedSyntheticCasesExerciseLocalCitationGuardsAgainstOptimisticSemanticResults() throws Exception {
        var mapper = new ObjectMapper();
        var cases = Files.readAllLines(GOLDEN).stream().filter(line -> !line.isBlank())
                .map(line -> {
                    try { return mapper.readTree(line); }
                    catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
                }).collect(java.util.stream.Collectors.toMap(row -> row.path("id").asText(), row -> row));
        var verifier = new SectionVerificationService((inputs, deadline) -> inputs.stream()
                .map(input -> new ClaimVerification(input.claimId(), CitationVerdict.SUPPORTED, "OPTIMISTIC_TEST_DOUBLE"))
                .toList());

        for (var id : List.of("GEN-003", "GEN-004", "GEN-006", "GEN-008", "GEN-010")) {
            var row = cases.get(id);
            assertThat(row).as(id).isNotNull();
            var candidate = row.path("candidateClaim").asText();
            assertThat(candidate).as(id).isNotBlank();
            var evidenceId = UUID.randomUUID();
            var evidence = new Evidence(UUID.randomUUID(), evidenceId, UUID.randomUUID(),
                    UUID.randomUUID(), "node-1", "合成资料", row.path("evidence").get(0).asText(),
                    0.9, new MarkdownLocator(List.of("合成资料"), "node-1"));
            var riskTypes = row.path("riskType").isNull() ? Set.<RiskType>of()
                    : Set.of(RiskType.valueOf(row.path("riskType").asText()));
            var claim = new Claim(UUID.randomUUID(), candidate, true,
                    Set.of(evidenceId), riskTypes);
            if (id.equals("GEN-008")) {
                assertThatThrownBy(() -> verifier.verify(List.of(claim), List.of(), Instant.now()))
                        .as(id).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("outside whitelist");
                assertThat(row.path("expectedDecision").asText()).isEqualTo("REFUSE");
                continue;
            }
            var actual = verifier.verify(List.of(claim), List.of(evidence), Instant.now())
                    .verifications().getFirst().verdict();
            var expected = switch (row.path("expectedDecision").asText()) {
                case "REFUSE" -> CitationVerdict.UNSUPPORTED;
                case "REQUIRE_HUMAN_REVIEW" -> CitationVerdict.HUMAN_CONFIRMATION_REQUIRED;
                default -> throw new IllegalArgumentException("Unsupported golden decision: " + id);
            };
            assertThat(actual).as(id).isEqualTo(expected);
        }
    }
}
