package com.yunjihai.rag.infrastructure.crypto;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacQuestionDigestTest {
    @Test
    void createsStableKeyedSha256DigestWithoutExposingTheKey() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        var digest = new HmacQuestionDigest(key);
        assertThat(digest.digest("同一个问题")).matches("[0-9a-f]{64}")
                .isEqualTo(digest.digest("同一个问题"))
                .isNotEqualTo(digest.digest("另一个问题"));
        assertThat(digest.toString()).doesNotContain(new String(key, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsKeysShorterThanThirtyTwoBytes() {
        assertThatThrownBy(() -> new HmacQuestionDigest(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
