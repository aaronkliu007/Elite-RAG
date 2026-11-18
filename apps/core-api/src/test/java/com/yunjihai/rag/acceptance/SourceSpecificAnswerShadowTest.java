package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.qa.domain.*;
import com.yunjihai.rag.retrieval.domain.TableLocator;
import com.yunjihai.rag.retrieval.domain.Evidence;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class SourceSpecificAnswerShadowTest {
    private static final String TABLE="项目\n预算类别\n金额（万元）\n审批日期及版本\n星河项目\n研发预算\n100\n未注明";

    @Test void acceptsOnlyTheCompleteExplicitRecordWithAmountUnitAndUnstatedField() {
        assertThat(check("星河项目预算记录甲记载多少？","05_星河项目预算记录甲",TABLE,TABLE)).isTrue();
        assertThat(check("星河预算记录甲是否注明审批日期？","05_星河项目预算记录甲",TABLE,TABLE)).isTrue();
    }

    @Test void rejectsWrongRecordMissingUnitAndPartialOrInferredClaims() {
        assertThat(check("星河项目预算记录乙记载多少？","05_星河项目预算记录甲",TABLE,TABLE)).isFalse();
        assertThat(check("星河项目预算记录甲记载多少？","05_星河项目预算记录甲",TABLE,"100")).isFalse();
        String missingUnit=TABLE.replace("金额（万元）","金额");
        assertThat(check("星河项目预算记录甲记载多少？","05_星河项目预算记录甲",missingUnit,missingUnit)).isFalse();
        assertThat(check("星河预算记录甲是否注明审批日期？","05_星河项目预算记录甲",TABLE,"未注明")).isFalse();
        assertThat(check("星河项目研发预算究竟是多少？","05_星河项目预算记录甲",TABLE,TABLE)).isFalse();
    }

    @Test void acceptsTheSameNodeDespiteTransientIdButRejectsSameNamedOtherDocumentOrVersion() {
        var space=UUID.randomUUID(); var document=UUID.randomUUID(); var version=UUID.randomUUID();
        var locator=new TableLocator("预算",1,2,1,4);
        var selected=new Evidence(space,UUID.randomUUID(),document,version,"node","05_星河项目预算记录甲",TABLE,1,locator);
        assertThat(boundCheck(selected,space,document,version,"node",locator)).isTrue();
        assertThat(boundCheck(selected,space,UUID.randomUUID(),version,"node",locator)).isFalse();
        assertThat(boundCheck(selected,space,document,UUID.randomUUID(),"node",locator)).isFalse();
        assertThat(boundCheck(selected,space,document,version,"other-node",locator)).isFalse();
        assertThat(boundCheck(selected,UUID.randomUUID(),document,version,"node",locator)).isFalse();
    }

    private static boolean boundCheck(Evidence expected,UUID space,UUID document,UUID version,String node,TableLocator locator) {
        var id=UUID.randomUUID();
        var source=new QaEvidence(id,space,document,version,node,expected.title(),TABLE,1,locator);
        var claim=new GroundedClaim(UUID.randomUUID(),TABLE,List.of(new EvidenceSupport(id,TABLE)));
        var answer=new CandidateAnswer(CandidateAnswer.Decision.ANSWER,TABLE,List.of(claim));
        return SourceSpecificAnswerShadow.supports("星河项目预算记录甲记载多少？",answer,
                new EvidenceBundle(List.of(source),TABLE.length()),expected);
    }

    private static boolean check(String question,String title,String excerpt,String claimText) {
        var id=UUID.randomUUID();
        var source=new QaEvidence(id,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"node",title,excerpt,1,
                new TableLocator("预算",1,2,1,4));
        var claim=new GroundedClaim(UUID.randomUUID(),claimText,List.of(new EvidenceSupport(id,excerpt)));
        var answer=new CandidateAnswer(CandidateAnswer.Decision.ANSWER,claimText,List.of(claim));
        var expected=new Evidence(source.spaceId(),UUID.randomUUID(),source.documentId(),source.versionId(),
                source.nodeId(),source.title(),source.excerpt(),1,source.locator());
        return SourceSpecificAnswerShadow.supports(question,answer,new EvidenceBundle(List.of(source),excerpt.length()),expected);
    }
}
