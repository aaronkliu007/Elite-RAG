package com.yunjihai.rag.qa.conflict;

import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicEvidenceConflictDetectorTest {
    private final DeterministicEvidenceConflictDetector detector=
            new DeterministicEvidenceConflictDetector();

    @ParameterizedTest
    @MethodSource("conflictingFacts")
    void detectsConflictingTypedFactsAcrossDifferentEvidence(String left,String right){
        assertThat(detector.hasConflict(List.of(evidence(1,left),evidence(2,right)))).isTrue();
    }

    static Stream<Arguments> conflictingFacts(){
        return Stream.of(
                Arguments.of("项目预算：1万元", "项目预算为2万元"),
                Arguments.of("项目预算设置为1万元", "项目预算：2万元"),
                Arguments.of("项目预算设为1万元", "项目预算为2万元"),
                Arguments.of("项目预算定为1万元", "项目预算：2万元"),
                Arguments.of("服务期限为12个月", "服务期限：24个月"),
                Arguments.of("生效日期：2026年1月1日", "生效日期为2026-02-01"),
                Arguments.of("折扣比例：10%", "折扣比例为20%"),
                Arguments.of("功能状态：启用", "功能状态为停用"));
    }

    @ParameterizedTest
    @MethodSource("factKeysWithUnicodeWhitespace")
    void normalizesUnicodeWhitespaceInsideSemanticFactKeys(String left){
        assertThat(detector.hasConflict(List.of(
                evidence(1,left),
                evidence(2,"服务期限：24个月")))).isTrue();
    }

    static Stream<String> factKeysWithUnicodeWhitespace(){
        return Stream.of(
                "服务 期限：12个月",
                "服务\u3000期限：12个月",
                "服务\u2003期限：12个月");
    }

    @Test
    void preservesLineBoundariesWhenExtractingMultipleFacts(){
        assertThat(detector.hasConflict(List.of(
                evidence(1,"项目预算：1万元\n服务期限：12个月"),
                evidence(2,"项目预算：1万元\n服务期限：24个月")))).isTrue();
    }

    @ParameterizedTest
    @MethodSource("equivalentFacts")
    void treatsEquivalentUnitsAndStatesAsTheSameValue(String left,String right){
        assertThat(detector.hasConflict(List.of(evidence(1,left),evidence(2,right)))).isFalse();
    }

    static Stream<Arguments> equivalentFacts(){
        return Stream.of(
                Arguments.of("项目预算：1万元", "项目预算为10000元"),
                Arguments.of("服务期限：1年", "服务期限为12个月"),
                Arguments.of("存储容量：1GB", "存储容量为1024MB"),
                Arguments.of("功能状态：启用", "功能状态为开启"));
    }

    @Test
    void matchesAQualifiedFactKeyToItsMeaningfulSuffix(){
        assertThat(detector.hasConflict(List.of(
                evidence(1,"公司差旅报销期限：30天"),
                evidence(2,"报销期限为60日")))).isTrue();
    }

    @Test
    void doesNotConfuseDifferentFactsThatShareOnlyAShortSuffix(){
        assertThat(detector.hasConflict(List.of(
                evidence(1,"报销期限：30天"),
                evidence(2,"付款期限：60天")))).isFalse();
    }

    @Test
    void doesNotCompareDifferentValuesInsideOneEvidenceItem(){
        assertThat(detector.hasConflict(List.of(
                evidence(1,"试用期限：30天。正式期限：60天。")))).isFalse();
    }

    @Test
    void normalizesUnicodeWhitespaceForDuplicateIdentityAndRejectsRealDivergence(){
        Evidence canonical=evidence(1,"报销期限：30 天");
        Evidence equivalent=new Evidence(
                canonical.spaceId(),id(2),canonical.documentId(),canonical.versionId(),canonical.nodeId(),
                canonical.title(),"报销期限: 30天",canonical.score(),canonical.locator());
        Evidence divergent=new Evidence(
                canonical.spaceId(),id(3),canonical.documentId(),canonical.versionId(),canonical.nodeId(),
                canonical.title(),"报销期限：60天",canonical.score(),canonical.locator());

        assertThat(detector.hasConflict(List.of(canonical,equivalent))).isFalse();
        assertThat(detector.hasConflict(List.of(canonical,divergent))).isTrue();
    }

    private static Evidence evidence(int suffix,String excerpt){
        return new Evidence(
                id(100),id(suffix),id(200+suffix),id(300+suffix),"node-"+suffix,
                "制度",excerpt,0.9,new MarkdownLocator(List.of("第一章"),"node-"+suffix));
    }

    private static UUID id(int suffix){
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix));
    }
}
