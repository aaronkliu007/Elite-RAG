package com.yunjihai.rag.qa.conflict;

import com.yunjihai.rag.ingestion.CanonicalTableCell;
import com.yunjihai.rag.qa.ExactQuoteCitationSupportVerifier;
import com.yunjihai.rag.qa.domain.*;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.TableLocator;

import java.text.Normalizer;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Narrow local opt-in policy; unknown syntax and any ambiguous source are refused. */
public final class BackupBudgetQuestionScopedPolicy implements QuestionScopedConflictPolicy {
    private static final Pattern QUESTION=Pattern.compile("^([\\p{IsHan}A-Za-z0-9]{2,30}项目)(的备份保留多久|多久组织一次恢复演练)[?？]?$");
    private final TableEvidenceSource tables;

    public BackupBudgetQuestionScopedPolicy(TableEvidenceSource tables) { this.tables=Objects.requireNonNull(tables); }
    @Override public String version() { return "backup-budget-v2"; }

    @Override public Scope classify(QaQuestion question, DeterministicEvidenceConflictDetector.ConflictPair pair, List<Evidence> candidates) {
        if(question==null || pair==null || candidates==null)return Scope.UNKNOWN;
        var asked=QUESTION.matcher(normalize(question.value()));
        if(!asked.matches() || !consistentCandidates(asked.group(1),asked.group(2).startsWith("的"),candidates))return Scope.UNKNOWN;
        var first=candidates.stream().filter(item->item.evidenceId().equals(pair.firstEvidenceId())).toList();
        var second=candidates.stream().filter(item->item.evidenceId().equals(pair.secondEvidenceId())).toList();
        if(first.size()!=1 || second.size()!=1 || pair.firstEvidenceId().equals(pair.secondEvidenceId()))return Scope.UNKNOWN;
        if(!independentTitle(first.getFirst()) || !independentTitle(second.getFirst()))return Scope.UNKNOWN;
        try {
            String left,right;
            if(pair.kind()==EvidenceConflictDetector.ConflictKind.STRUCTURED_TABLE
                    && first.getFirst().locator() instanceof TableLocator && second.getFirst().locator() instanceof TableLocator) {
                left=budgetProject(tables.load(first.getFirst())); right=budgetProject(tables.load(second.getFirst()));
            } else return Scope.UNKNOWN;
            return left!=null && left.equals(right) ? Scope.UNRELATED : Scope.UNKNOWN;
        } catch(RuntimeException invalid) { return Scope.UNKNOWN; }
    }

    private static boolean independentTitle(Evidence item) {
        String title=normalize(item.title());
        if(!title.matches("(?:预算|研发预算|(?:[0-9]+_)?[\\p{IsHan}A-Za-z0-9]{2,30}项目预算记录[甲乙]?)"))return false;
        return !(item.locator() instanceof TableLocator table)
                || normalize(table.tableName()).matches("(?:预算|预算表|研发预算|预算明细)");
    }

    private static Pattern sourcePattern(String project) {
        return Pattern.compile("^"+Pattern.quote(project)+
                "(?:每天凌晨[\\p{IsHan}0-9]{1,4}点执行一次全量备份,)?(?<backup>备份保留\\d{1,4}(?:天|日))。"
                +"(?:运维负责人[\\p{IsHan}]{2,5}(?<drill>每(?:月|周|年)组织一次恢复演练)。)?$");
    }

    private boolean consistentCandidates(String project,boolean backup,List<Evidence> candidates) {
        String value=null;
        try {
            for(var item:candidates) {
                if(item.locator() instanceof TableLocator) {
                    if(!independentTitle(item) || !tables.hasStandaloneBudgetContext(item)
                            || budgetProject(tables.load(item))==null)return false;
                    continue;
                }
                if(!(item.locator() instanceof MarkdownLocator location)
                        || !tables.hasStandaloneBackupContext(item)
                        || !normalize(item.title()).matches("(?:制度|(?:[0-9]+_)?"+Pattern.quote(project)+"(?:数据)?备份制度)")
                        || location.headingPath().stream().anyMatch(heading ->
                                !normalize(heading).matches("(?:章节|备份制度|数据备份制度)")))return false;
                String text=normalizeSource(item.excerpt());
                var source=sourcePattern(project).matcher(text);
                if(!source.matches())return false;
                String found=source.group(backup?"backup":"drill");
                if(found==null || (value!=null && !value.equals(found)))return false;
                value=found;
            }
        } catch(RuntimeException unavailable) { return false; }
        return value!=null;
    }

    private static String budgetProject(List<CanonicalTableCell> cells) {
        if(cells==null || (cells.size()!=6 && cells.size()!=8))return null;
        int width=cells.size()/2; String[][] grid=new String[2][width];
        for(var cell:cells) {
            if(cell==null || cell.row()<0 || cell.row()>1 || cell.column()<0 || cell.column()>=width
                    || cell.rowSpan()!=1 || cell.columnSpan()!=1 || cell.text()==null || grid[cell.row()][cell.column()]!=null)return null;
            grid[cell.row()][cell.column()]=normalize(cell.text());
        }
        if(width==4 && (!"审批日期及版本".equals(grid[0][3]) || !"未注明".equals(grid[1][3])))return null;
        if(!"项目".equals(grid[0][0]) || !"预算类别".equals(grid[0][1])
                || !("金额(万元)".equals(grid[0][2]) || "金额(元)".equals(grid[0][2]))
                || grid[1][0]==null || !grid[1][0].matches("[\\p{IsHan}A-Za-z0-9]{2,30}项目")
                || !"研发预算".equals(grid[1][1]) || grid[1][2]==null
                || !grid[1][2].matches("[+\\-−]?\\d+(?:\\.\\d+)?"))return null;
        return grid[1][0];
    }

    @Override public AnswerRelevance verifyAnswer(QaQuestion question,CandidateAnswer answer,EvidenceBundle evidence) {
        if(question==null || answer==null || evidence==null || answer.decision()!=CandidateAnswer.Decision.ANSWER
                || answer.claims().size()!=1 || !new ExactQuoteCitationSupportVerifier().isSupported(answer,evidence))return AnswerRelevance.UNKNOWN;
        var q=QUESTION.matcher(normalize(question.value())); if(!q.matches())return AnswerRelevance.UNKNOWN;
        var sourcePattern=sourcePattern(q.group(1));
        var claim=answer.claims().getFirst(); if(claim.supports().isEmpty())return AnswerRelevance.UNKNOWN;
        for(var support:claim.supports()) {
            var matches=evidence.evidence().stream().filter(item->item.evidenceId().equals(support.evidenceId())).toList();
            if(matches.size()!=1)return AnswerRelevance.UNKNOWN;
            var text=normalizeSource(matches.getFirst().excerpt()); var source=sourcePattern.matcher(text);
            if(!source.matches())return AnswerRelevance.UNKNOWN;
            boolean backup=q.group(2).startsWith("的"); String extracted=source.group(backup?"backup":"drill");
            var sentences=text.split("。");
            if(extracted==null || sentences.length<(backup?1:2))return AnswerRelevance.UNKNOWN;
            String result=normalize(answer.answer()).replaceFirst("。$","");
            String claimText=normalize(claim.text()).replaceFirst("。$","");
            if(!result.equals(claimText) || !(result.equals(extracted)||result.equals(sentences[backup?0:1])))return AnswerRelevance.UNKNOWN;
        }
        return AnswerRelevance.RELEVANT;
    }
    private static String normalize(String value) { return Normalizer.normalize(value,Normalizer.Form.NFKC).strip(); }
    private static String normalizeSource(String value) { return value.strip().replace('，', ','); }
}
