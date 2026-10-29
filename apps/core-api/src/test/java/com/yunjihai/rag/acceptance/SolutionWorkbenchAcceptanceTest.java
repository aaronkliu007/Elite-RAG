package com.yunjihai.rag.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.solution.feedback.FeedbackReason;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract for the synthetic generation fixture; real pilot quality requires separate measured cases. */
class SolutionWorkbenchAcceptanceTest {
    @Test void syntheticGoldenCasesCoverFormatsRefusalPermissionsAndHumanRisk() throws Exception {
        assertThat(FeedbackReason.values()).extracting(Enum::name).containsExactly(
                "ACCEPTED", "FACT_ERROR", "MISSING_EVIDENCE", "IRRELEVANT", "OUTDATED",
                "STYLE", "DUPLICATE", "SCOPE_MISMATCH", "OTHER");
        var path = Path.of("..", "..", "evaluation", "generation", "golden-v1.jsonl");
        var mapper = new ObjectMapper();
        var ids = new HashSet<String>();
        var formats = new HashSet<String>();
        var decisions = new HashSet<String>();
        var scenarios = new HashSet<String>();
        var lines = Files.readAllLines(path).stream().filter(line -> !line.isBlank()).toList();
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
}
