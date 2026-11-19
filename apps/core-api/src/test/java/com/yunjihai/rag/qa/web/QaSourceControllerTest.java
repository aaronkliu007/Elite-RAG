package com.yunjihai.rag.qa.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.qa.QaSourceAccessPort;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.yunjihai.rag.qa.web.QaTestHarness.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QaSourceControllerTest {
    @Test
    void revokedAnswerCannotRevealPreviouslyCitedSourceMetadata() {
        var calls=new AtomicInteger();
        QaSourceAccessPort access=(request,evidence,actor,at)->{calls.incrementAndGet();return Optional.empty();};
        var controller=new QaSourceController(access,Clock.fixed(NOW,ZoneOffset.UTC),(request,actor)->Optional.empty());
        assertThatThrownBy(()->controller.get(auth(ACTOR),id(2),id(3))).isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThat(calls).hasValue(0);
    }
    @Test
    void reauthorizesEveryOpenAndReturnsOnlySafeLocationMetadata() {
        UUID request=id(2),evidence=id(3),document=id(4),version=id(5);
        var calls=new AtomicInteger();
        QaSourceAccessPort access=(requestId,evidenceId,actorId,at)->{
            calls.incrementAndGet();
            return Optional.of(new QaSourceAccessPort.QaSource(request,evidence,id(6),document,
                    version,"node-1","制度文件",
                    new MarkdownLocator(List.of("第一章"),"node-1")));
        };
        var controller=new QaSourceController(access,Clock.fixed(NOW,ZoneOffset.UTC),(id,actor)->Optional.of(
                new com.yunjihai.rag.qa.domain.QaOutcome.Answered(id,"已回答",List.of(
                        new com.yunjihai.rag.qa.domain.CitationClaim(UUID.randomUUID(),"已回答",List.of(evidence))),false)));
        var first=controller.get(auth(ACTOR),request,evidence);
        var second=controller.get(auth(ACTOR),request,evidence);
        assertThat(calls).hasValue(2);
        assertThat(second).isEqualTo(first);
        assertThat(first.documentId()).isEqualTo(document);
        assertThat(first.displayName()).isEqualTo("制度文件");
        assertThat(first.versionId()).isEqualTo(version);
        assertThat(first.previewUrl()).isEqualTo("/api/v1/documents/"+document+"/preview");
        assertThat(first.downloadUrl()).isEqualTo("/api/v1/documents/"+document+"/download");
        assertThat(first.location().type()).isEqualTo("MARKDOWN");
        assertThat(first.toString()).doesNotContain("正文","quote","excerpt");
    }

    @Test
    void deniedMissingAndUnownedSourcesUseSameNonLeakingFailure() {
        var controller=new QaSourceController((request,evidence,actor,at)->Optional.empty(),
                Clock.fixed(NOW,ZoneOffset.UTC),(request,actor)->Optional.empty());
        assertThatThrownBy(()->controller.get(auth(ACTOR),id(2),id(3)))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(()->controller.get(auth(id(9)),id(2),id(3)))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
    }
}
