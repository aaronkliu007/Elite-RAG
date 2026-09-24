package com.yunjihai.rag.ingestion;

import java.io.InputStream;

@FunctionalInterface
public interface UploadSpooler {

    SpooledUpload spool(InputStream source, long maxBytes);
}
