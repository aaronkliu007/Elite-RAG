package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.qa.conflict.DeterministicEvidenceConflictDetector.ConflictPair;
import com.yunjihai.rag.qa.conflict.EvidenceConflictDetector.ConflictKind;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.TableLocator;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class SourceSpecificConflictDiagnosticTest {
    @Test
    void recognizesARequestForOneNamedRecordWithoutResolvingTheConflict() {
        var a=record(1,"05_星河项目预算记录甲");
        var b=record(2,"06_星河项目预算记录乙");
        var pair=new ConflictPair(a.evidenceId(),b.evidenceId(),ConflictKind.STRUCTURED_TABLE);
        var evidence=Map.of(a.evidenceId(),a,b.evidenceId(),b);
        assertThat(SourceSpecificConflictDiagnostic.target("星河项目预算记录甲记载多少？",pair,evidence)).contains(a.evidenceId());
        assertThat(SourceSpecificConflictDiagnostic.target("星河预算记录甲是否注明审批日期？",pair,evidence)).contains(a.evidenceId());
        assertThat(SourceSpecificConflictDiagnostic.target("星河项目预算记录乙记载多少？",pair,evidence)).contains(b.evidenceId());
        assertThat(SourceSpecificConflictDiagnostic.target("星河预算记录乙是否注明版本号？",pair,evidence)).contains(b.evidenceId());
    }

    @Test
    void rejectsAmbiguousOrBroaderQuestionsAndMismatchedRecords() {
        var a=record(1,"05_星河项目预算记录甲");
        var b=record(2,"06_星河项目预算记录乙");
        var pair=new ConflictPair(a.evidenceId(),b.evidenceId(),ConflictKind.STRUCTURED_TABLE);
        var evidence=Map.of(a.evidenceId(),a,b.evidenceId(),b);
        for (String question:List.of("星河项目研发预算究竟是多少？", "星河预算记录甲是否正确？",
                "星河预算记录甲记载多少以及最终预算是多少？", "北辰预算记录甲记载多少？")) {
            assertThat(SourceSpecificConflictDiagnostic.target(question,pair,evidence)).isEmpty();
        }
        assertThat(SourceSpecificConflictDiagnostic.target("星河预算记录甲记载多少？",pair,Map.of(a.evidenceId(),a))).isEmpty();
        var other=record(3,"06_北辰项目预算记录乙");
        assertThat(SourceSpecificConflictDiagnostic.target("星河预算记录甲记载多少？",
                new ConflictPair(a.evidenceId(),other.evidenceId(),ConflictKind.STRUCTURED_TABLE),
                Map.of(a.evidenceId(),a,other.evidenceId(),other))).isEmpty();
        assertThat(SourceSpecificConflictDiagnostic.target("星河预算记录甲记载多少？",
                new ConflictPair(a.evidenceId(),b.evidenceId(),ConflictKind.IDENTITY),evidence)).isEmpty();
    }

    @Test
    void selectedEvidenceUsesStableSourceIdentityInsteadOfTransientEvidenceId() {
        var candidate=record(1,"05_星河项目预算记录甲");
        var selected=new Evidence(candidate.spaceId(),UUID.randomUUID(),candidate.documentId(),candidate.versionId(),
                candidate.nodeId(),candidate.title(),candidate.excerpt(),candidate.score(),candidate.locator());
        assertThat(SourceSpecificConflictDiagnostic.isSelected(candidate,List.of(selected))).isTrue();
        var otherVersion=new Evidence(candidate.spaceId(),UUID.randomUUID(),candidate.documentId(),UUID.randomUUID(),
                candidate.nodeId(),candidate.title(),candidate.excerpt(),candidate.score(),candidate.locator());
        assertThat(SourceSpecificConflictDiagnostic.isSelected(candidate,List.of(otherVersion))).isFalse();
    }

    private static Evidence record(int id,String title) {
        var evidenceId=new UUID(0,id);
        return new Evidence(new UUID(0,99),evidenceId,new UUID(1,id),new UUID(2,id),"node-"+id,
                title,"预算表",1.0,new TableLocator("预算",1,2,1,3));
    }
}
