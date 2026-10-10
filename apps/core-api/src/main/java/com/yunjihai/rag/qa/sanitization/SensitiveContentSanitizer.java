package com.yunjihai.rag.qa.sanitization;

import com.yunjihai.rag.qa.domain.EvidenceBundle;

public interface SensitiveContentSanitizer {
    SanitizationResult sanitize(EvidenceBundle evidence);

    SanitizedText sanitizeText(String text);
}
