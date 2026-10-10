package com.yunjihai.rag.qa.model;

import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.domain.QaQuestion;

import java.util.Objects;
import java.util.regex.Pattern;

public record GroundedAnswerPrompt(
        QaQuestion question,
        EvidenceBundle evidence,
        String language,
        String policyVersion,
        String promptVersion,
        int maxOutputCodePoints) {
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})?");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public GroundedAnswerPrompt {
        Objects.requireNonNull(question, "question must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (language == null || !LANGUAGE.matcher(language).matches()) {
            throw new IllegalArgumentException("language is invalid");
        }
        if (policyVersion == null || !VERSION.matcher(policyVersion).matches()
                || promptVersion == null || !VERSION.matcher(promptVersion).matches()) {
            throw new IllegalArgumentException("prompt version is invalid");
        }
        if (maxOutputCodePoints < 1 || maxOutputCodePoints > 2_000) {
            throw new IllegalArgumentException("max output length is invalid");
        }
    }
}
