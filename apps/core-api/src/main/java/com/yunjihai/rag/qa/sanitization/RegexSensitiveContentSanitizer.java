package com.yunjihai.rag.qa.sanitization;

import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.domain.QaEvidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class RegexSensitiveContentSanitizer implements SensitiveContentSanitizer {
    private static final int BULK_PERSONAL_INFORMATION_THRESHOLD = 5;
    private static final List<Rule> STANDARD_RULES = List.of(
            new Rule("PRIVATE_KEY", Pattern.compile(
                    "(?s)-----BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-----.*?-----END(?: [A-Z0-9]+)? PRIVATE KEY-----"),
                    "[PRIVATE_KEY_BLOCKED]", true),
            new Rule("NATIONAL_ID", Pattern.compile("(?<![0-9])(?:[1-9][0-9]{16}[0-9Xx])(?![0-9A-Za-z])"),
                    "[NATIONAL_ID]", false),
            new Rule("PHONE", Pattern.compile("(?<![0-9])1[3-9][0-9]{9}(?![0-9])"),
                    "[PHONE]", false),
            new Rule("EMAIL", Pattern.compile("(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9.-])"),
                    "[EMAIL]", false),
            new Rule("BEARER_TOKEN", Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"),
                    "Bearer [REDACTED]", false),
            new Rule("API_KEY", Pattern.compile("(?i)(?:api[_-]?key\\s*[:=]\\s*|sk-)[A-Za-z0-9._-]{4,}"),
                    "[API_KEY]", false),
            new Rule("CONNECTION_STRING", Pattern.compile(
                    "(?i)\\b(?:jdbc:[^\\s,;，；。]+|(?:postgres(?:ql)?|mysql|mongodb(?:\\+srv)?):\\/\\/[^\\s,;，；。]+)"),
                    "[CONNECTION_STRING]", false));

    private final List<Rule> rules;

    public RegexSensitiveContentSanitizer(Set<String> businessTerms) {
        Objects.requireNonNull(businessTerms, "businessTerms must not be null");
        var configured = new ArrayList<>(STANDARD_RULES);
        businessTerms.stream()
                .peek(term -> {
                    if (term == null || term.isBlank() || term.codePointCount(0, term.length()) > 200) {
                        throw new IllegalArgumentException("business sensitive term is invalid");
                    }
                })
                .sorted()
                .forEach(term -> configured.add(new Rule(
                        "BUSINESS_TERM", Pattern.compile(Pattern.quote(term)), "[SENSITIVE_TERM]", false)));
        this.rules = List.copyOf(configured);
    }

    @Override
    public SanitizationResult sanitize(EvidenceBundle evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        var sanitized = new ArrayList<QaEvidence>(evidence.evidence().size());
        var counts = new LinkedHashMap<String, Integer>();
        boolean blocked = false;
        int totalCodePoints = 0;
        for (var item : evidence.evidence()) {
            var title = sanitizeText(item.title());
            var text = sanitizeText(item.excerpt());
            title.hitCounts().forEach((category, count) -> counts.merge(category, count, Integer::sum));
            text.hitCounts().forEach((category, count) -> counts.merge(category, count, Integer::sum));
            blocked |= title.blocked() || text.blocked();
            totalCodePoints += text.value().codePointCount(0, text.value().length());
            sanitized.add(new QaEvidence(
                    item.evidenceId(), item.spaceId(), item.documentId(), item.versionId(),
                    item.nodeId(), title.value(), text.value(), item.score(), item.locator()));
        }
        return new SanitizationResult(
                new EvidenceBundle(sanitized, totalCodePoints), counts, blocked);
    }

    @Override
    public SanitizedText sanitizeText(String text) {
        Objects.requireNonNull(text, "text must not be null");
        String sanitized = text;
        var counts = new LinkedHashMap<String, Integer>();
        boolean blocked = false;
        int personalInformationCount = 0;
        for (var rule : rules) {
            var matcher = rule.pattern().matcher(sanitized);
            int count = 0;
            while (matcher.find()) count++;
            if (count == 0) continue;
            counts.merge(rule.category(), count, Integer::sum);
            if (rule.category().equals("PHONE")
                    || rule.category().equals("NATIONAL_ID")
                    || rule.category().equals("EMAIL")) {
                personalInformationCount += count;
            }
            blocked |= rule.blocked();
            sanitized = matcher.replaceAll(java.util.regex.Matcher.quoteReplacement(rule.replacement()));
        }
        if (personalInformationCount >= BULK_PERSONAL_INFORMATION_THRESHOLD) blocked = true;
        return new SanitizedText(sanitized, counts, blocked);
    }

    private record Rule(String category, Pattern pattern, String replacement, boolean blocked) {
    }
}
