package com.yunjihai.rag.retrieval.selection;

import com.yunjihai.rag.retrieval.domain.ScoredCandidate;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Local ordering fallback only; does not filter candidates or alter their authorization or scores. */
final class LocalLexicalFallback {
    private LocalLexicalFallback() { }

    static List<ScoredCandidate> rank(String query, List<ScoredCandidate> candidates) {
        Set<String> terms = tokens(query);
        if (terms.isEmpty()) return candidates;
        record Item(ScoredCandidate candidate, int overlap, int index) { }
        var items = new ArrayList<Item>();
        for (int index = 0; index < candidates.size(); index++) {
            var candidate = candidates.get(index);
            Set<String> textTerms = tokens(candidate.candidate().title() + "\n" + candidate.candidate().text());
            int overlap = 0;
            for (String term : terms) if (textTerms.contains(term)) overlap++;
            items.add(new Item(candidate, overlap, index));
        }
        items.sort(Comparator.comparingInt(Item::overlap).reversed().thenComparingInt(Item::index));
        return items.stream().map(Item::candidate).toList();
    }

    private static Set<String> tokens(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        var result = new HashSet<String>();
        var latin = new StringBuilder();
        int previousHan = -1;
        for (int cp : normalized.codePoints().toArray()) {
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                flushLatin(latin, result);
                if (previousHan >= 0) result.add(new String(Character.toChars(previousHan)) + new String(Character.toChars(cp)));
                previousHan = cp;
            } else {
                previousHan = -1;
                if ((cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9')) latin.appendCodePoint(cp);
                else flushLatin(latin, result);
            }
        }
        flushLatin(latin, result);
        return result;
    }

    private static void flushLatin(StringBuilder value, Set<String> result) {
        if (value.length() >= 2) result.add(value.toString());
        value.setLength(0);
    }
}
