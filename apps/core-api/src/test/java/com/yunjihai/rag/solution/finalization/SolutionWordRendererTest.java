package com.yunjihai.rag.solution.finalization;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SolutionWordRendererTest {
    @Test void writesChineseHeadingsAndUntrustedBodyAsLiteralText() throws Exception {
        var body = "正文 <script>alert('x')</script> & 核验";
        var evidence = UUID.randomUUID();
        var input = new SolutionWordRenderer.Document(
                "实施方案", 2, Instant.parse("2026-10-29T00:00:00Z"),
                "a".repeat(64), List.of(new SolutionWordRenderer.RequirementLine(
                        "建设目标", List.of("文档 document-1｜版本 version-1｜页码 1"))),
                List.of(new SolutionWordRenderer.Chapter(1, "总体设计", body,
                        List.of("证据 " + evidence + "｜文档 document-1｜版本 version-1｜节点 node-1"))));

        byte[] bytes = new SolutionWordRenderer().render(input);

        assertTrue(bytes.length > 1000);
        try (var parsed = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var text = parsed.getParagraphs().stream().map(p -> p.getText()).toList();
            assertTrue(text.contains("实施方案"));
            assertTrue(text.contains(body));
            assertTrue(text.stream().anyMatch(line -> line.contains("页码 1")));
            assertTrue(text.stream().anyMatch(line -> line.contains(evidence.toString())));
            for (var relation : parsed.getPackage().getRelationships()) {
                assertNotEquals("EXTERNAL", relation.getTargetMode().name());
            }
        }
    }
}
