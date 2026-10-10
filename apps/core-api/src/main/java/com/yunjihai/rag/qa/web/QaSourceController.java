package com.yunjihai.rag.qa.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.qa.QaSourceAccessPort;
import com.yunjihai.rag.retrieval.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.util.*;

@RestController
@RequestMapping("/api/qa/questions/{requestId}/sources")
@Profile("local")
@ConditionalOnProperty(prefix="rag.qa",name="hmac-key")
public class QaSourceController {
    private final QaSourceAccessPort sources;
    private final Clock clock;

    public QaSourceController(QaSourceAccessPort sources,Clock clock) {
        this.sources=Objects.requireNonNull(sources);
        this.clock=Objects.requireNonNull(clock);
    }

    @GetMapping("/{evidenceId}")
    public QaDtos.SourceResponse get(
            Authentication authentication,
            @PathVariable UUID requestId,
            @PathVariable UUID evidenceId) {
        UUID actorId=QaController.userId(authentication);
        var source=sources.findAuthorized(requestId,evidenceId,actorId,clock.instant())
                .orElseThrow(AccessDeniedAsNotFoundException::new);
        String documentPath="/api/v1/documents/"+source.documentId();
        return new QaDtos.SourceResponse(source.documentId(),source.title(),
                source.versionId(),location(source.locator()),documentPath+"/preview",
                documentPath+"/download");
    }

    private static QaDtos.SourceLocation location(SourceLocator locator) {
        return switch(locator) {
            case PageLocator page -> new QaDtos.SourceLocation("PAGE",map(
                    "pageNumber",page.pageNumber(),"boundingBox",page.boundingBox()));
            case SlideLocator slide -> new QaDtos.SourceLocation(
                    "SLIDE",Map.of("slideNumber",slide.slideNumber()));
            case TableLocator table -> new QaDtos.SourceLocation("TABLE",map(
                    "tableName",table.tableName(),"startRow",table.startRow(),
                    "endRow",table.endRow(),"startColumn",table.startColumn(),
                    "endColumn",table.endColumn()));
            case MarkdownLocator markdown -> new QaDtos.SourceLocation("MARKDOWN",map(
                    "headingPath",markdown.headingPath(),"nodeId",markdown.nodeId()));
        };
    }

    private static Map<String,Object> map(Object... values) {
        var result=new LinkedHashMap<String,Object>();
        for(int index=0;index<values.length;index+=2) {
            if(values[index+1]!=null)result.put((String)values[index],values[index+1]);
        }
        return result;
    }
}
