package com.yunjihai.rag.ingestion;

import java.nio.file.Path;

@FunctionalInterface
public interface ParserTransport {

    String parse(ParserJob job, Path sourceFile);
}
