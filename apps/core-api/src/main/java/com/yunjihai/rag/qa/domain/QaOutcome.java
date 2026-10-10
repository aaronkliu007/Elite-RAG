package com.yunjihai.rag.qa.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public sealed interface QaOutcome permits QaOutcome.Answered, QaOutcome.Refused, QaOutcome.Failed {
    Pattern FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    UUID requestId();

    QaStatus status();

    record Answered(UUID requestId, String answer, List<CitationClaim> claims, boolean degraded)
            implements QaOutcome {
        public Answered {
            Objects.requireNonNull(requestId, "requestId must not be null");
            Objects.requireNonNull(answer, "answer must not be null");
            if (answer.isBlank() || answer.codePointCount(0, answer.length()) > 2_000) {
                throw new IllegalArgumentException("answer length is invalid");
            }
            claims = List.copyOf(Objects.requireNonNull(claims, "claims must not be null"));
            if (claims.isEmpty() || claims.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("answered outcome requires claims");
            }
        }

        @Override
        public QaStatus status() { return QaStatus.ANSWERED; }
    }

    record Refused(UUID requestId, RefusalReason reason, boolean degraded) implements QaOutcome {
        public Refused {
            Objects.requireNonNull(requestId, "requestId must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }

        @Override
        public QaStatus status() { return QaStatus.REFUSED; }
    }

    record Failed(UUID requestId, String code) implements QaOutcome {
        public Failed {
            Objects.requireNonNull(requestId, "requestId must not be null");
            Objects.requireNonNull(code, "failure code must not be null");
            if (!FAILURE_CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("failure code is invalid");
            }
        }

        @Override
        public QaStatus status() { return QaStatus.FAILED; }
    }
}
