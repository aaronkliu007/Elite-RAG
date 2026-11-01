package com.yunjihai.rag.qa.evaluation;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Stable, length-delimited fingerprint of the complete evaluation catalog entry. */
public final class EvaluationDatasetFingerprint {
    private static final Comparator<EvaluationCase.EvidenceRef> REF_ORDER = Comparator
            .comparing((EvaluationCase.EvidenceRef value)->value.documentId().toString())
            .thenComparing(EvaluationCase.EvidenceRef::nodeId);
    private static final Comparator<EvaluationCase.ReviewedEvidence> REVIEWED_SOURCE_ORDER = Comparator
            .comparing((EvaluationCase.ReviewedEvidence value)->value.documentId().toString())
            .thenComparing(value->value.versionId().toString()).thenComparing(EvaluationCase.ReviewedEvidence::nodeId)
            .thenComparing(EvaluationCase.ReviewedEvidence::contentDigest)
            .thenComparing(EvaluationCase.ReviewedEvidence::titleDigest)
            .thenComparing(EvaluationCase.ReviewedEvidence::locatorDigest);

    private EvaluationDatasetFingerprint() { }

    public static String sha256(EvaluationDataset dataset) {
        Objects.requireNonNull(dataset);
        return digest(out->{
            write(out,"evaluation-dataset-fingerprint-v1");
            write(out,dataset.id().toString()); write(out,dataset.version());
            write(out,dataset.promptVersion()); write(out,dataset.sanitizationVersion());
            out.writeBoolean(dataset.active()); out.writeInt(dataset.cases().size());
            for(var item:dataset.cases()) {
                write(out,item.id().toString()); write(out,item.evaluationActorId().toString());
                write(out,item.question()); out.writeBoolean(item.shouldRefuse());
                write(out,item.reviewQuestionDigest());
                var expected=item.expectedEvidence().stream().sorted(REF_ORDER).toList();
                out.writeInt(expected.size());
                for(var ref:expected) { write(out,ref.documentId().toString()); write(out,ref.nodeId()); }
                writeStrings(out,item.forbiddenDocumentIds().stream().map(Object::toString).sorted().toList());
                writeStrings(out,item.forbiddenNodeIds().stream().sorted().toList());
                writeStrings(out,item.coverageTags().stream().sorted().toList());
                var reviews=item.reviewedClaims().stream().sorted(Comparator.comparing(
                        review->digest(nested->writeReview(nested,review)))).toList();
                out.writeInt(reviews.size());
                for(var review:reviews)writeReview(out,review);
            }
        });
    }

    private static void writeReview(DataOutputStream out,EvaluationCase.ReviewedClaim review) throws IOException {
        write(out,review.text()); write(out,review.reviewReference()); write(out,review.verdict().name());
        var sources=review.supportingEvidence().stream().sorted(REVIEWED_SOURCE_ORDER).toList();
        out.writeInt(sources.size());
        for(var source:sources) {
            write(out,source.documentId().toString()); write(out,source.versionId().toString()); write(out,source.nodeId());
            write(out,source.contentDigest()); write(out,source.titleDigest()); write(out,source.locatorDigest());
        }
    }

    private static void writeStrings(DataOutputStream out,List<String> values) throws IOException {
        out.writeInt(values.size());
        for(String value:values)write(out,value);
    }

    private static void write(DataOutputStream out,String value) throws IOException {
        if(value==null) { out.writeInt(-1); return; }
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static String digest(Writer writer) {
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),hash))) {
                writer.write(out);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch(NoSuchAlgorithmException|IOException failure) {
            throw new IllegalStateException("evaluation fingerprint unavailable",failure);
        }
    }

    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }
}
