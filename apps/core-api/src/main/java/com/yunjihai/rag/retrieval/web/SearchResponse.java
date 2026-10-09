package com.yunjihai.rag.retrieval.web;

import com.yunjihai.rag.retrieval.domain.DegradationReason;
import com.yunjihai.rag.retrieval.domain.EvidencePack;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.NormalizedBoundingBox;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.retrieval.domain.SlideLocator;
import com.yunjihai.rag.retrieval.domain.SourceLocator;
import com.yunjihai.rag.retrieval.domain.TableLocator;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record SearchResponse(
        UUID requestId,
        List<EvidenceView> evidence,
        boolean degraded,
        List<DegradationReason> degradationReasons,
        SearchStats stats,
        Instant completedAt) {

    static SearchResponse from(EvidencePack pack) {
        return new SearchResponse(
                pack.requestId(),
                pack.evidence().stream().map(EvidenceView::from).toList(),
                pack.degraded(),
                pack.degradationReasons().stream()
                        .sorted(Comparator.comparing(Enum::name))
                        .toList(),
                SearchStats.from(pack),
                pack.completedAt());
    }

    public record EvidenceView(
            UUID evidenceId,
            UUID documentId,
            UUID versionId,
            String nodeId,
            String title,
            String excerpt,
            double score,
            LocatorView locator) {

        private static EvidenceView from(com.yunjihai.rag.retrieval.domain.Evidence evidence) {
            return new EvidenceView(
                    evidence.evidenceId(),
                    evidence.documentId(),
                    evidence.versionId(),
                    evidence.nodeId(),
                    evidence.title(),
                    evidence.excerpt(),
                    evidence.score(),
                    SearchResponse.locator(evidence.locator()));
        }
    }

    public record SearchStats(
            int lexicalCount,
            int vectorCount,
            int fusedCount,
            int reauthorizedCount,
            int selectedCount) {

        private static SearchStats from(EvidencePack pack) {
            var stats = pack.stats();
            return new SearchStats(
                    stats.lexicalCount(),
                    stats.vectorCount(),
                    stats.fusedCount(),
                    stats.reauthorizedCount(),
                    stats.selectedCount());
        }
    }

    public sealed interface LocatorView
            permits PageLocatorView, SlideLocatorView, TableLocatorView, MarkdownLocatorView {
        String type();
    }

    public record BoundingBoxView(double left, double top, double right, double bottom) {
        private static BoundingBoxView from(NormalizedBoundingBox box) {
            return box == null ? null : new BoundingBoxView(
                    box.left(), box.top(), box.right(), box.bottom());
        }
    }

    public record PageLocatorView(
            String type,
            int pageNumber,
            BoundingBoxView boundingBox) implements LocatorView {
    }

    public record SlideLocatorView(String type, int slideNumber) implements LocatorView {
    }

    public record TableLocatorView(
            String type,
            String tableName,
            int startRow,
            int endRow,
            int startColumn,
            int endColumn) implements LocatorView {
    }

    public record MarkdownLocatorView(
            String type,
            List<String> headingPath,
            String nodeId) implements LocatorView {
    }

    private static LocatorView locator(SourceLocator locator) {
        if (locator instanceof PageLocator page) {
            return new PageLocatorView(
                    "PAGE", page.pageNumber(), BoundingBoxView.from(page.boundingBox()));
        }
        if (locator instanceof SlideLocator slide) {
            return new SlideLocatorView("SLIDE", slide.slideNumber());
        }
        if (locator instanceof TableLocator table) {
            return new TableLocatorView(
                    "TABLE", table.tableName(), table.startRow(), table.endRow(),
                    table.startColumn(), table.endColumn());
        }
        if (locator instanceof MarkdownLocator markdown) {
            return new MarkdownLocatorView(
                    "MARKDOWN", markdown.headingPath(), markdown.nodeId());
        }
        throw new IllegalArgumentException("不支持的来源定位类型");
    }
}
