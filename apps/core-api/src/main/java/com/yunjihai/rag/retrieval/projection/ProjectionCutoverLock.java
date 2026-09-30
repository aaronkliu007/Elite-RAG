package com.yunjihai.rag.retrieval.projection;

import java.time.Duration;
import java.util.function.Supplier;

public interface ProjectionCutoverLock {
    <T> T withShared(Supplier<T> action);

    <T> T withExclusive(Duration timeout, Supplier<T> action);
}
