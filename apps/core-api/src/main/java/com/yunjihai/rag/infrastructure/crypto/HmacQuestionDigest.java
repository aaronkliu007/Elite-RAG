package com.yunjihai.rag.infrastructure.crypto;

import com.yunjihai.rag.qa.QuestionDigestPort;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;

public final class HmacQuestionDigest implements QuestionDigestPort {
    private final byte[] key;

    public HmacQuestionDigest(byte[] key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length < 32) throw new IllegalArgumentException("HMAC key must contain at least 32 bytes");
        this.key = key.clone();
    }

    @Override
    public String digest(String value) {
        Objects.requireNonNull(value, "value must not be null");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }

    @Override
    public String toString() { return "HmacQuestionDigest[algorithm=HmacSHA256,key=REDACTED]"; }
}
