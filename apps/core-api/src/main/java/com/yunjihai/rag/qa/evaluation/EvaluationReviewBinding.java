package com.yunjihai.rag.qa.evaluation;

import com.yunjihai.rag.qa.QuestionDigestPort;
import java.util.Objects;

final class EvaluationReviewBinding {
    private EvaluationReviewBinding() { }
    static String create(QuestionDigestPort hasher,String digest) {
        Objects.requireNonNull(hasher); Objects.requireNonNull(digest);
        return hasher.digest("qa-evaluation-review-v1:"+digest);
    }
}
