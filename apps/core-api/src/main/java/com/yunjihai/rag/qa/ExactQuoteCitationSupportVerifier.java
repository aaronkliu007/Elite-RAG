package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.domain.CandidateAnswer;
import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.domain.QaEvidence;

import java.text.Normalizer;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ExactQuoteCitationSupportVerifier implements CitationSupportVerifier {
    @Override
    public boolean isSupported(CandidateAnswer answer, EvidenceBundle evidence) {
        if (answer == null || evidence == null || answer.decision() != CandidateAnswer.Decision.ANSWER) {
            return false;
        }
        Map<java.util.UUID, QaEvidence> known = evidence.evidence().stream()
                .collect(Collectors.toUnmodifiableMap(QaEvidence::evidenceId, Function.identity()));
        boolean claimsSupported = answer.claims().stream().allMatch(claim -> {
            String normalizedClaim = normalize(claim.text());
            boolean claimFoundInQuote = claim.supports().stream()
                    .map(support -> normalize(support.quote()))
                    .anyMatch(quote -> quote.contains(normalizedClaim));
            if (!claimFoundInQuote) return false;
            return claim.supports().stream().allMatch(support -> {
                QaEvidence source = known.get(support.evidenceId());
                return source != null
                        && normalize(source.excerpt()).contains(normalize(support.quote()));
            });
        });
        if (!claimsSupported) return false;
        String expectedAnswer = answer.claims().stream()
                .map(claim -> claim.text())
                .collect(Collectors.joining("\n"));
        return normalize(answer.answer()).equals(normalize(expectedAnswer));
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        var result = new StringBuilder(normalized.length());
        boolean previousWhitespace = true;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (!previousWhitespace) result.append(' ');
                previousWhitespace = true;
            } else {
                result.appendCodePoint(codePoint);
                previousWhitespace = false;
            }
        }
        if (!result.isEmpty() && result.charAt(result.length() - 1) == ' ') {
            result.setLength(result.length() - 1);
        }
        return result.toString();
    }
}
