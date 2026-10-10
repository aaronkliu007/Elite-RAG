package com.yunjihai.rag.qa.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yunjihai.rag.qa.domain.*;

import java.time.Instant;
import java.util.*;

public final class QaDtos {
    private QaDtos() { }

    public static final class AskQuestionRequest {
        private final String question;

        @JsonCreator
        public AskQuestionRequest(@JsonProperty("question") String question) {
            this.question=question;
        }

        @JsonProperty("question")
        public String question() {
            return question;
        }

        @JsonAnySetter
        public void rejectUnknownField(String name,Object value) {
            throw new IllegalArgumentException("unsupported request field: "+name);
        }
    }

    public record CitationView(UUID claimId,String text,List<UUID> evidenceIds) {
        public CitationView {
            Objects.requireNonNull(claimId);
            Objects.requireNonNull(text);
            evidenceIds=List.copyOf(Objects.requireNonNull(evidenceIds));
        }
        static CitationView from(CitationClaim claim) {
            return new CitationView(claim.claimId(),claim.text(),claim.evidenceIds());
        }
    }

    public record QuestionResponse(
            UUID requestId,
            QaStatus status,
            String answer,
            String refusal,
            boolean degraded,
            List<CitationView> citations,
            Instant completedAt) {
        public QuestionResponse {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(status);
            citations=List.copyOf(Objects.requireNonNull(citations));
            Objects.requireNonNull(completedAt);
        }

        static QuestionResponse from(QaOutcome outcome,Instant completedAt) {
            return switch(outcome) {
                case QaOutcome.Answered answered -> new QuestionResponse(
                        answered.requestId(),answered.status(),answered.answer(),null,
                        answered.degraded(),answered.claims().stream().map(CitationView::from).toList(),
                        completedAt);
                case QaOutcome.Refused refused -> new QuestionResponse(
                        refused.requestId(),refused.status(),null,refused.reason().name(),
                        refused.degraded(),List.of(),completedAt);
                case QaOutcome.Failed failed -> new QuestionResponse(
                        failed.requestId(),failed.status(),null,null,failed.degraded(),List.of(),completedAt);
            };
        }
    }

    public record SourceLocation(String type,Map<String,Object> coordinates) {
        public SourceLocation {
            if(type==null||type.isBlank())throw new IllegalArgumentException("location type is required");
            coordinates=Map.copyOf(Objects.requireNonNull(coordinates));
        }
    }

    public record SourceResponse(
            UUID documentId,
            String displayName,
            UUID versionId,
            SourceLocation location,
            String previewUrl,
            String downloadUrl) {
        public SourceResponse {
            Objects.requireNonNull(documentId);
            if(displayName==null||displayName.isBlank())
                throw new IllegalArgumentException("displayName is required");
            Objects.requireNonNull(versionId);
            Objects.requireNonNull(location);
            Objects.requireNonNull(previewUrl);
            Objects.requireNonNull(downloadUrl);
        }
    }
}
