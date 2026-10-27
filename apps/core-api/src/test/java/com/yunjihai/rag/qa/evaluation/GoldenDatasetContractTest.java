package com.yunjihai.rag.qa.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenDatasetContractTest {
    @Test
    void goldenDatasetContainsAtLeastFiftyUniqueSanitizedCasesAcrossRequiredCoverage() throws Exception {
        var root=Path.of("..","..").toAbsolutePath().normalize();
        var dataset=new ObjectMapper().findAndRegisterModules().readValue(
                root.resolve("contracts/qa/golden-dataset-v1.json").toFile(),EvaluationDataset.class);

        assertThat(dataset.active()).isTrue();
        assertThat(dataset.cases()).hasSizeGreaterThanOrEqualTo(50);
        assertThat(dataset.cases()).extracting(EvaluationCase::id).doesNotHaveDuplicates();
        assertThat(dataset.cases()).extracting(EvaluationCase::question)
                .allMatch(question->question.contains("虚构"));
        assertThat(dataset.cases().stream().flatMap(item->item.coverageTags().stream()).collect(java.util.stream.Collectors.toSet()))
                .contains("WORD","PDF","PPT","EXCEL","MARKDOWN","PNG_OCR","CROSS_DOCUMENT",
                        "SYNONYM","ABBREVIATION","CONFLICT","REFUSAL","PERMISSION_TRAP");
    }
}
