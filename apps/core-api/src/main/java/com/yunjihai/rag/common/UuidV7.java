package com.yunjihai.rag.common;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class UuidV7 {

    private static final long MAX_UNIX_MILLIS = 0xFFFF_FFFF_FFFFL;
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate(Instant at) {
        Objects.requireNonNull(at);
        var unixMillis = at.toEpochMilli();
        if (unixMillis < 0 || unixMillis > MAX_UNIX_MILLIS) {
            throw new IllegalArgumentException("UUIDv7 时间超出可表示范围");
        }
        var randomA = RANDOM.nextInt(1 << 12);
        var randomB = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
        var mostSignificantBits = unixMillis << 16 | 0x7000L | randomA;
        var leastSignificantBits = 0x8000_0000_0000_0000L | randomB;
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
