package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.qa.conflict.DeterministicEvidenceConflictDetector.ConflictPair;
import com.yunjihai.rag.qa.conflict.EvidenceConflictDetector.ConflictKind;
import com.yunjihai.rag.retrieval.domain.Evidence;

import java.text.Normalizer;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Offline experiment only; never used to authorize, filter or answer production requests. */
final class QuestionScopedConflictShadow {
    enum Scope { EXPLICITLY_UNRELATED, UNKNOWN }
    private static final Pattern BACKUP_QUESTION=Pattern.compile(
            "^[\\p{IsHan}A-Za-z0-9]{2,30}的?备份保留(?:期限|时长)?(?:为|是)?(?:多久|多长时间|多少天)[?？]?$");
    private static final Pattern RESTORE_DRILL_QUESTION=Pattern.compile(
            "^[\\p{IsHan}A-Za-z0-9]{2,30}多久组织一次恢复演练[?？]?$");
    private static final Pattern SIMPLE_PROJECT_BUDGET=Pattern.compile(
            "^[\\p{IsHan}A-Za-z0-9]{2,30}项目研发预算(?:为|是|[:：])"
                    + "[+\\-−]?\\d+(?:\\.\\d+)?(?:万元|元)[。.]?$");

    private QuestionScopedConflictShadow() { }

    static Scope assess(String question,ConflictPair pair,Map<java.util.UUID,Evidence> evidence) {
        Objects.requireNonNull(question);
        Objects.requireNonNull(pair);
        Objects.requireNonNull(evidence);
        Evidence first=evidence.get(pair.firstEvidenceId()),second=evidence.get(pair.secondEvidenceId());
        if(first==null||second==null) return Scope.UNKNOWN;
        String q=normalize(question);
        if(pair.kind()!=ConflictKind.TYPED_FACT || q.contains("预算") || q.contains("费用")
                || !isSupportedQuestion(q))return Scope.UNKNOWN;
        return SIMPLE_PROJECT_BUDGET.matcher(normalize(first.excerpt())).matches()
                && SIMPLE_PROJECT_BUDGET.matcher(normalize(second.excerpt())).matches()
                ?Scope.EXPLICITLY_UNRELATED:Scope.UNKNOWN;
    }

    static Scope assess(String question, ConflictPair pair, Map<java.util.UUID,Evidence> evidence,
                        com.yunjihai.rag.qa.conflict.TableEvidenceSource tableSource) {
        Objects.requireNonNull(question);
        Objects.requireNonNull(pair);
        Objects.requireNonNull(evidence);
        Objects.requireNonNull(tableSource);
        if (pair.kind()!=ConflictKind.STRUCTURED_TABLE) return assess(question,pair,evidence);
        String q=normalize(question);
        if (!isSupportedQuestion(q)) return Scope.UNKNOWN;
        Evidence first=evidence.get(pair.firstEvidenceId()), second=evidence.get(pair.secondEvidenceId());
        if (first==null || second==null
                || !(first.locator() instanceof com.yunjihai.rag.retrieval.domain.TableLocator)
                || !(second.locator() instanceof com.yunjihai.rag.retrieval.domain.TableLocator)) return Scope.UNKNOWN;
        try {
            String project=singleBudgetProject(tableSource.load(first));
            String otherProject=singleBudgetProject(tableSource.load(second));
            return project!=null && project.equals(otherProject) ? Scope.EXPLICITLY_UNRELATED : Scope.UNKNOWN;
        } catch (RuntimeException unavailableOrAmbiguous) {
            return Scope.UNKNOWN;
        }
    }

    private static boolean isSupportedQuestion(String question) {
        return !question.contains("预算") && !question.contains("费用")
                && (BACKUP_QUESTION.matcher(question).matches() || RESTORE_DRILL_QUESTION.matcher(question).matches());
    }

    static String singleBudgetProject(java.util.List<com.yunjihai.rag.ingestion.CanonicalTableCell> cells) {
        if (cells==null || (cells.size()!=6 && cells.size()!=8)) return null;
        int width=cells.size()/2;
        String[][] grid=new String[2][width];
        for (var cell:cells) {
            if (cell==null || cell.row()<0 || cell.row()>1 || cell.column()<0 || cell.column()>=width
                    || cell.rowSpan()!=1 || cell.columnSpan()!=1 || cell.text()==null
                    || grid[cell.row()][cell.column()]!=null) return null;
            grid[cell.row()][cell.column()]=normalize(cell.text());
        }
        if (width==4 && (!"审批日期及版本".equals(grid[0][3]) || !"未注明".equals(grid[1][3]))) return null;
        if (!"项目".equals(grid[0][0]) || !"预算类别".equals(grid[0][1])
                || !("金额(万元)".equals(grid[0][2]) || "金额(元)".equals(grid[0][2]))
                || grid[1][0]==null || !grid[1][0].matches("[\\p{IsHan}A-Za-z0-9]{2,30}项目")
                || !"研发预算".equals(grid[1][1]) || grid[1][2]==null
                || !grid[1][2].matches("[+\\-−]?\\d+(?:\\.\\d+)?")) return null;
        return grid[1][0];
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("\\s+","").strip();
    }
}
