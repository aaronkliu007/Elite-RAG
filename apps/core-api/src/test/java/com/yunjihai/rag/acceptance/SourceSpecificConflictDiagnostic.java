package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.qa.conflict.DeterministicEvidenceConflictDetector.ConflictPair;
import com.yunjihai.rag.retrieval.domain.Evidence;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.text.Normalizer;
import java.util.regex.Pattern;
import java.util.List;
import com.yunjihai.rag.qa.conflict.EvidenceConflictDetector.ConflictKind;
import com.yunjihai.rag.retrieval.domain.TableLocator;

/** Offline diagnosis only: never used to allow or filter production answers. */
final class SourceSpecificConflictDiagnostic {
    private static final Pattern QUESTION=Pattern.compile("^([\\p{IsHan}A-Za-z0-9]{2,30}?)(?:项目)?预算记录([甲乙])(?:记载多少|是否注明(?:审批日期|版本号))\\??$");
    private static final Pattern TITLE=Pattern.compile("^\\d+_([\\p{IsHan}A-Za-z0-9]{2,30}?)(?:项目)?预算记录([甲乙])$");
    private SourceSpecificConflictDiagnostic() { }
    static Optional<String> matchingProject(String question,String title) {
        if(question==null || title==null) return Optional.empty();
        var q=QUESTION.matcher(normalize(question));
        var t=TITLE.matcher(normalize(title));
        if(!q.matches() || !t.matches() || !q.group(1).equals(t.group(1)) || !q.group(2).equals(t.group(2)))
            return Optional.empty();
        return Optional.of(q.group(1));
    }
    static boolean isSelected(Evidence candidate,List<Evidence> selected) {
        return selected.stream().anyMatch(item->item.spaceId().equals(candidate.spaceId())
                && item.documentId().equals(candidate.documentId()) && item.versionId().equals(candidate.versionId())
                && item.nodeId().equals(candidate.nodeId()) && item.title().equals(candidate.title())
                && item.excerpt().equals(candidate.excerpt()));
    }
    static Optional<UUID> target(String question,ConflictPair pair,Map<UUID,Evidence> evidence) {
        if(question==null || pair==null || evidence==null || pair.kind()!=ConflictKind.STRUCTURED_TABLE) return Optional.empty();
        var q=QUESTION.matcher(normalize(question));
        if(!q.matches()) return Optional.empty();
        var first=evidence.get(pair.firstEvidenceId());
        var second=evidence.get(pair.secondEvidenceId());
        if(first==null || second==null || !(first.locator() instanceof TableLocator)
                || !(second.locator() instanceof TableLocator)) return Optional.empty();
        var a=TITLE.matcher(normalize(first.title()));
        var b=TITLE.matcher(normalize(second.title()));
        if(!a.matches() || !b.matches() || !a.group(1).equals(q.group(1))
                || !b.group(1).equals(q.group(1)) || a.group(2).equals(b.group(2))) return Optional.empty();
        return Optional.of(a.group(2).equals(q.group(2)) ? first.evidenceId() : second.evidenceId());
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("\\s+", "");
    }
}
