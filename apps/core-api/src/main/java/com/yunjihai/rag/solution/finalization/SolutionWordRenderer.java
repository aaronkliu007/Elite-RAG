package com.yunjihai.rag.solution.finalization;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/** A deliberately small text-only DOCX renderer; model content never becomes OOXML markup. */
public final class SolutionWordRenderer {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年MM月dd日")
            .withZone(ZoneId.of("Asia/Shanghai"));

    public byte[] render(Document input) {
        Objects.requireNonNull(input);
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            paragraph(document, input.title(), "Title", 20, true, ParagraphAlignment.CENTER);
            paragraph(document, "方案第 " + input.versionNumber() + " 版", "Subtitle", 12, false,
                    ParagraphAlignment.CENTER);
            paragraph(document, "定稿日期：" + DATE.format(input.finalizedAt()), null, 10, false,
                    ParagraphAlignment.CENTER);
            paragraph(document, "内容摘要 SHA-256：" + input.contentHash(), null, 9, false,
                    ParagraphAlignment.CENTER);
            paragraph(document, "需求摘要", "Heading1", 16, true, ParagraphAlignment.LEFT);
            for (int i = 0; i < input.requirements().size(); i++) {
                var requirement = input.requirements().get(i);
                paragraph(document, (i + 1) + ". " + requirement.text(), null, 11, false,
                        ParagraphAlignment.LEFT);
                for (var source : requirement.sources()) {
                    paragraph(document, "来源：" + source, null, 9, false, ParagraphAlignment.LEFT);
                }
            }
            for (var chapter : input.chapters()) {
                paragraph(document, chapter.order() + ". " + chapter.title(), "Heading1", 16, true,
                        ParagraphAlignment.LEFT);
                // POI's setText escapes XML; no Markdown/HTML conversion or external content loading.
                for (var line : chapter.body().split("\\R", -1)) {
                    paragraph(document, line, null, 11, false, ParagraphAlignment.LEFT);
                }
                if (!chapter.citations().isEmpty()) {
                    paragraph(document, "引用证据", "Heading2", 12, true, ParagraphAlignment.LEFT);
                    for (var citation : chapter.citations()) {
                        paragraph(document, citation, null, 9, false, ParagraphAlignment.LEFT);
                    }
                }
            }
            document.write(output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("SOLUTION_WORD_EXPORT_FAILED", failure);
        }
    }

    private static void paragraph(XWPFDocument document, String value, String style,
            int fontSize, boolean bold, ParagraphAlignment alignment) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(alignment);
        paragraph.setSpacingAfter(160);
        if (style != null) paragraph.setStyle(style);
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("Microsoft YaHei");
        run.setFontSize(fontSize);
        run.setBold(bold);
        run.setText(value);
    }

    public record Document(String title, long versionNumber, Instant finalizedAt, String contentHash,
            List<RequirementLine> requirements, List<Chapter> chapters) {
        public Document {
            Objects.requireNonNull(title);
            Objects.requireNonNull(finalizedAt);
            Objects.requireNonNull(contentHash);
            requirements = List.copyOf(requirements);
            chapters = List.copyOf(chapters);
            if (title.isBlank() || versionNumber < 1 || !contentHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid export document");
            }
        }
    }

    public record RequirementLine(String text, List<String> sources) {
        public RequirementLine {
            Objects.requireNonNull(text);
            sources = List.copyOf(sources);
            if (text.isBlank()) throw new IllegalArgumentException("invalid requirement text");
        }
    }

    public record Chapter(int order, String title, String body, List<String> citations) {
        public Chapter {
            Objects.requireNonNull(title);
            Objects.requireNonNull(body);
            citations = List.copyOf(citations);
            if (order < 1 || title.isBlank() || body.isBlank()) {
                throw new IllegalArgumentException("invalid export chapter");
            }
        }
    }
}
