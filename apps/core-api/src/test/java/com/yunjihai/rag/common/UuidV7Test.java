package com.yunjihai.rag.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void generatesUniqueRfc9562VersionSevenIdentifiersWithEmbeddedTimestamp() {
        var at = Instant.parse("2026-09-24T08:00:00.123Z");
        var values = IntStream.range(0, 100)
                .mapToObj(ignored -> UuidV7.generate(at))
                .toList();

        assertThat(values).doesNotHaveDuplicates();
        assertThat(values).allSatisfy(value -> {
            assertThat(value.version()).isEqualTo(7);
            assertThat(value.variant()).isEqualTo(2);
            assertThat(value.getMostSignificantBits() >>> 16).isEqualTo(at.toEpochMilli());
        });
    }
}
