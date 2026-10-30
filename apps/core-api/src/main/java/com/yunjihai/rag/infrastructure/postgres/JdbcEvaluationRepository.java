package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.qa.evaluation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.*;
import java.util.*;

public final class JdbcEvaluationRepository implements EvaluationRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Map<UUID,EvaluationDataset> datasets;
    private final EvaluationTraceRegistry traces;

    public JdbcEvaluationRepository(JdbcClient jdbc,ObjectMapper json,Map<UUID,EvaluationDataset> datasets) {
        this(jdbc,json,datasets,new EvaluationTraceRegistry());
    }

    public JdbcEvaluationRepository(JdbcClient jdbc,ObjectMapper json,Map<UUID,EvaluationDataset> datasets,
                                    EvaluationTraceRegistry traces) {
        this.jdbc=Objects.requireNonNull(jdbc); this.json=Objects.requireNonNull(json);
        this.datasets=Map.copyOf(Objects.requireNonNull(datasets));
        this.traces=Objects.requireNonNull(traces);
        if(this.datasets.values().stream().anyMatch(dataset->!dataset.active()))
            throw new IllegalArgumentException("evaluation catalog contains inactive dataset");
    }

    @Override public Optional<EvaluationDataset> findDataset(UUID datasetId) {
        return Optional.ofNullable(datasets.get(Objects.requireNonNull(datasetId)));
    }

    @Override public Observation observe(UUID requestId) {
        Objects.requireNonNull(requestId);
        try {
            List<EvidenceRow> rows=jdbc.sql("""
                    SELECT evidence_id,document_id,node_id,ordinal
                    FROM qa_evidence_snapshot WHERE request_id=:requestId ORDER BY ordinal
                    """).param("requestId",requestId).query((row,n)->new EvidenceRow(
                            row.getObject("evidence_id",UUID.class),row.getObject("document_id",UUID.class),
                            row.getString("node_id"),row.getInt("ordinal"))).list();
            Set<EvaluationCase.EvidenceRef> top10=new LinkedHashSet<>();
            Set<UUID> evidenceIds=new LinkedHashSet<>();
            rows.forEach(row->{
                evidenceIds.add(row.evidenceId());
                if(row.ordinal()<10)top10.add(new EvaluationCase.EvidenceRef(row.documentId(),row.nodeId()));
            });
            EvaluationTraceRegistry.Trace trace=traces.consume(requestId);
            return new Observation(trace.retrievalObserved() ? trace.retrievedTop10() : top10,
                    evidenceIds,trace.modelRequest(),trace.modelResponse());
        } catch(DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override public void save(EvaluationRun run) {
        Objects.requireNonNull(run);
        try {
            jdbc.sql("""
                    INSERT INTO qa_evaluation_run(
                        id,dataset_id,dataset_version,code_version,policy_version,prompt_version,
                        sanitization_version,model_config_version,started_at,completed_at,passed,metrics,assessment)
                    VALUES (:id,:datasetId,:datasetVersion,:codeVersion,:policyVersion,:promptVersion,
                        :sanitizationVersion,:modelConfigVersion,:startedAt,:completedAt,:passed,
                        CAST(:metrics AS jsonb),CAST(:assessment AS jsonb))
                    """).param("id",run.runId()).param("datasetId",run.datasetId())
                    .param("datasetVersion",run.datasetVersion()).param("codeVersion",run.codeVersion())
                    .param("policyVersion",run.policyVersion()).param("promptVersion",run.promptVersion())
                    .param("sanitizationVersion",run.sanitizationVersion())
                    .param("modelConfigVersion",run.modelConfigVersion()).param("startedAt",utc(run.startedAt()))
                    .param("completedAt",utc(run.completedAt())).param("passed",run.assessment().passed())
                    .param("metrics",write(run.metrics())).param("assessment",write(run.assessment())).update();
        } catch(DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override public Optional<EvaluationRun> findRun(UUID runId) {
        Objects.requireNonNull(runId);
        try {
            return jdbc.sql("""
                    SELECT id,dataset_id,dataset_version,code_version,policy_version,prompt_version,
                           sanitization_version,model_config_version,started_at,completed_at,metrics::text,assessment::text
                    FROM qa_evaluation_run WHERE id=:id
                    """).param("id",runId).query((row,n)->new EvaluationRun(
                            row.getObject("id",UUID.class),row.getObject("dataset_id",UUID.class),
                            row.getString("dataset_version"),row.getString("code_version"),row.getString("policy_version"),
                            row.getString("prompt_version"),row.getString("sanitization_version"),
                            row.getString("model_config_version"),row.getObject("started_at",OffsetDateTime.class).toInstant(),
                            row.getObject("completed_at",OffsetDateTime.class).toInstant(),
                            read(row.getString("metrics"),EvaluationMetrics.class),
                            read(row.getString("assessment"),EvaluationGate.Assessment.class))).optional();
        } catch(DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private String write(Object value) {
        try{return json.writeValueAsString(value);}
        catch(JsonProcessingException failure){throw new IllegalStateException("evaluation serialization failed",failure);}
    }
    private <T>T read(String value,Class<T> type) {
        try{return json.readValue(value,type);}
        catch(JsonProcessingException failure){throw new IllegalStateException("evaluation data is invalid",failure);}
    }
    private static OffsetDateTime utc(Instant value){return OffsetDateTime.ofInstant(value,ZoneOffset.UTC);}
    private record EvidenceRow(UUID evidenceId,UUID documentId,String nodeId,int ordinal){ }
}
