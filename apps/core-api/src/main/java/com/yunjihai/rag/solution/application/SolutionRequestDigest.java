package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.retrieval.domain.*;
import com.yunjihai.rag.solution.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

final class SolutionRequestDigest {
    private final StringBuilder canonical = new StringBuilder();

    static String create(SolutionCommandService.CreateInput input) {
        var value = new SolutionRequestDigest();
        value.add("CREATE").add(input.spaceId()).add(SolutionName.of(input.name()).value())
                .add(input.expectedRevision()).items(input.items());
        return value.digest();
    }

    static String requirements(SolutionCommandService.ReviseRequirementsInput input) {
        var value = new SolutionRequestDigest();
        value.add("REVISE_REQUIREMENTS").add(input.solutionId()).add(input.expectedRevision())
                .add(input.changeReason()).items(input.items());
        return value.digest();
    }

    static String confirm(String action, SolutionCommandService.ConfirmInput input) {
        return new SolutionRequestDigest().add(action).add(input.solutionId())
                .add(input.versionId()).add(input.expectedRevision()).digest();
    }

    static String outline(SolutionCommandService.ReviseOutlineInput input) {
        var value = new SolutionRequestDigest();
        value.add("REVISE_OUTLINE").add(input.solutionId()).add(input.expectedRevision())
                .add(input.nodes().size());
        for (var node : input.nodes()) {
            value.add(node.sectionId()).add(node.title()).add(node.level()).add(node.order())
                    .add(node.required()).add(node.objective()).add(node.retrievalHint());
            var risks = node.riskTypes().stream().map(Enum::name).sorted().toList();
            value.add(risks.size());
            risks.forEach(value::add);
        }
        return value.digest();
    }

    private SolutionRequestDigest items(List<RequirementItem> items) {
        add(items.size());
        for (var item : items) {
            add(item.itemId()).add(item.category().name()).add(item.text()).add(item.sources().size());
            for (var source : item.sources()) {
                add(source.documentVersion().documentId()).add(source.documentVersion().versionId());
                switch (source.locator()) {
                    case PageLocator page -> {
                        add("PAGE").add(page.pageNumber());
                        var box = page.boundingBox();
                        add(box == null);
                        if (box != null) add(box.left()).add(box.top()).add(box.right()).add(box.bottom());
                    }
                    case SlideLocator slide -> add("SLIDE").add(slide.slideNumber());
                    case TableLocator table -> add("TABLE").add(table.tableName()).add(table.startRow())
                            .add(table.endRow()).add(table.startColumn()).add(table.endColumn());
                    case MarkdownLocator markdown -> {
                        add("MARKDOWN").add(markdown.headingPath().size());
                        markdown.headingPath().forEach(this::add);
                        add(markdown.nodeId());
                    }
                }
            }
        }
        return this;
    }

    private SolutionRequestDigest add(Object value) {
        String text = value == null ? "<null>" : String.valueOf(value);
        canonical.append(text.length()).append(':').append(text);
        return this;
    }

    private String digest() {
        try {
            var bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
