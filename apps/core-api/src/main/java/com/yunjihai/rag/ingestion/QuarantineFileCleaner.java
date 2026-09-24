package com.yunjihai.rag.ingestion;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface QuarantineFileCleaner {

    boolean delete(Path path) throws IOException;
}
