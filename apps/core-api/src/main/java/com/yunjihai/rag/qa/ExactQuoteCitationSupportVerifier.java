package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.domain.CandidateAnswer;
import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.domain.QaEvidence;

import java.text.Normalizer;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ExactQuoteCitationSupportVerifier implements CitationSupportVerifier {
    private static final int MAX_CLAIMS = 100;
    private static final int MAX_TOTAL_SUPPORTS = 225;
    private static final String CHINESE_NUMERALS = "零〇一二三四五六七八九十百千万亿两壹贰叁肆伍陆柒捌玖拾佰仟";
    private static final Pattern NEGATION_BEFORE_EXTRACT = Pattern.compile(
            "(?:(?:未经[\\p{IsHan}]{0,8})|(?:不|未|无|非|没有|尚未|拒绝|禁止)"
                    + "(?:\\s*(?:正式|已经|予以|被|获得|进行|及时|完成|能够|可以|能|可|再|得|予|会|获)){0,4})\\s*$");
    private static final Pattern NUMERIC_QUALIFIER_BEFORE_EXTRACT = Pattern.compile(
            "(?:不低于|不少于|不超过|不高于|大于|小于|超过|至少|至多|(?<!预)约为|(?<!预)约|预计|最低|最高|不足)\\s*$");
    private static final Pattern NUMERIC_UNIT_AT_END = Pattern.compile(
            "(?:万元|元|天|日|周|年|个月|月|人|名|台|%|GB|MB|TPS|QPS)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NUMERIC_QUALIFIER_AFTER_EXTRACT = Pattern.compile(
            "^\\s*(?:(?:及)?(?:以上|以下)|以内|以外|左右|上下|"
                    + "(?:至|到|[-~–—])\\s*[+\\-负]?[\\p{Nd}" + CHINESE_NUMERALS + "])");
    private static final Pattern CHINESE_AMOUNT_IN_EXTRACT = Pattern.compile(
            "[零〇一二三四五六七八九十百千万亿两壹贰叁肆伍陆柒捌玖拾佰仟]+\\s*(?:元|天|日|周|年|个月|月|人|台|%)");

    @Override
    public boolean isSupported(CandidateAnswer answer, EvidenceBundle evidence) {
        if (answer == null || evidence == null || answer.decision() != CandidateAnswer.Decision.ANSWER) {
            return false;
        }
        if (answer.claims().size() > MAX_CLAIMS
                || answer.claims().stream().mapToInt(claim -> claim.supports().size()).sum() > MAX_TOTAL_SUPPORTS) {
            return false;
        }
        String expectedAnswer = answer.claims().stream()
                .map(claim -> claim.text())
                .collect(Collectors.joining("\n"));
        if (!normalize(answer.answer()).equals(normalize(expectedAnswer))) return false;
        Map<java.util.UUID, QaEvidence> known = evidence.evidence().stream()
                .collect(Collectors.toUnmodifiableMap(QaEvidence::evidenceId, Function.identity()));
        boolean claimsSupported = answer.claims().stream().allMatch(claim -> {
            String normalizedClaim = normalize(claim.text());
            boolean claimFoundInQuote = claim.supports().stream()
                    .map(support -> normalize(support.quote()))
                    .anyMatch(quote -> containsWithSafeBoundaries(quote, normalizedClaim));
            if (!claimFoundInQuote) return false;
            return claim.supports().stream().allMatch(support -> {
                QaEvidence source = known.get(support.evidenceId());
                return source != null
                        && containsWithSafeBoundaries(normalize(source.excerpt()), normalize(support.quote()));
            });
        });
        return claimsSupported;
    }

    private static boolean containsWithSafeBoundaries(String whole, String fragment) {
        if (fragment.isEmpty()) return false;
        int occurrence = whole.indexOf(fragment);
        if (occurrence < 0) return false;
        boolean containsAmount = startsNumeric(fragment) || fragment.codePoints().anyMatch(Character::isDigit)
                || CHINESE_AMOUNT_IN_EXTRACT.matcher(fragment).find();
        while (occurrence >= 0) {
            int prefixStart = Math.max(0, occurrence - 16);
            String prefix = whole.substring(prefixStart, occurrence);
            if (NEGATION_BEFORE_EXTRACT.matcher(prefix).find()
                    || (containsAmount && NUMERIC_QUALIFIER_BEFORE_EXTRACT.matcher(prefix).find())
                    || (containsAmount && cutsNumericQualifier(whole, fragment, occurrence))
                    || cutsNumber(whole, fragment, occurrence)) return false;
            occurrence = whole.indexOf(fragment, occurrence + 1);
        }
        return true;
    }

    private static boolean cutsNumericQualifier(String whole, String fragment, int occurrence) {
        int last=fragment.codePointBefore(fragment.length());
        if (!isNumeral(last) && !NUMERIC_UNIT_AT_END.matcher(fragment).find()) return false;
        int after=occurrence+fragment.length();
        return after<whole.length() && NUMERIC_QUALIFIER_AFTER_EXTRACT.matcher(whole.substring(after)).find();
    }
    private static boolean cutsNumber(String whole, String fragment, int occurrence) {
        int last = fragment.codePointBefore(fragment.length());
        if (startsNumeric(fragment)) {
            int before = occurrence - 1;
            while (before >= 0 && Character.isWhitespace(whole.charAt(before))) before--;
            if (before >= 0) {
                int previous = whole.codePointBefore(before + 1);
                if (isNumeral(previous) || previous == '-' || previous == '+' || previous == '\u2212' || previous == '负') return true;
                int previousStart = before + 1 - Character.charCount(previous);
                if (isNumericSeparator(previous)
                        && previousStart > 0 && isNumeral(whole.codePointBefore(previousStart))) return true;
            }
        }
        int after = occurrence + fragment.length();
        if (isNumeral(last) && after < whole.length()) {
            int next = whole.codePointAt(after);
            if (isNumeral(next)) return true;
            int following = after + Character.charCount(next);
            if (isNumericSeparator(next) && following < whole.length()) {
                int followingCodePoint = whole.codePointAt(following);
                if (isNumeral(followingCodePoint)) return true;
                if ((next == 'e' || next == 'E') && (followingCodePoint == '+' || followingCodePoint == '-')
                        && following + 1 < whole.length() && isNumeral(whole.codePointAt(following + 1))) return true;
            }
        }
        return false;
    }

    private static boolean startsNumeric(String fragment) {
        int first = fragment.codePointAt(0);
        int secondIndex = Character.charCount(first);
        return isNumeral(first) || ((first == '.' || first == '-' || first == '+' || first == '\u2212')
                && secondIndex < fragment.length() && isNumeral(fragment.codePointAt(secondIndex)));
    }

    private static boolean isNumeral(int codePoint) {
        return Character.isDigit(codePoint) || CHINESE_NUMERALS.indexOf(codePoint) >= 0;
    }

    private static boolean isNumericSeparator(int codePoint) {
        return codePoint == '.' || codePoint == ',' || codePoint == 'e' || codePoint == 'E'
                || codePoint == '/' || codePoint == ':' || codePoint == '-' || codePoint == '\u2212';
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
