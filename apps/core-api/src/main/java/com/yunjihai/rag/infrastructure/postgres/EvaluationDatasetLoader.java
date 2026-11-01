package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.qa.evaluation.EvaluationDataset;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class EvaluationDatasetLoader {
    private static final int MAX_BYTES=10 * 1024 * 1024;
    private EvaluationDatasetLoader() { }

    static EvaluationDataset load(String configuredPath) {
        try (InputStream input=open(configuredPath)) {
            byte[] bytes=input.readNBytes(MAX_BYTES+1);
            if(bytes.length>MAX_BYTES)throw new IllegalStateException("evaluation dataset exceeds size limit");
            EvaluationDataset dataset=new ObjectMapper().findAndRegisterModules()
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readValue(bytes,EvaluationDataset.class);
            if(dataset==null)throw new IllegalStateException("evaluation dataset is empty");
            return dataset;
        } catch(IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("evaluation dataset cannot be loaded or is invalid");
        }
    }

    private static InputStream open(String configuredPath) throws IOException {
        if(configuredPath==null||configuredPath.isBlank()) {
            InputStream bundled=EvaluationDatasetLoader.class.getResourceAsStream("/contracts/qa/golden-dataset-v1.json");
            if(bundled==null)throw new IllegalStateException("bundled evaluation dataset is missing");
            return bundled;
        }
        Path path=Path.of(configuredPath);
        if(!path.isAbsolute())throw new IllegalStateException("evaluation dataset path must be absolute");
        return Files.newInputStream(path);
    }
}
