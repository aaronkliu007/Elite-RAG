package com.yunjihai.rag.ingestion;

import java.io.InputStream;

public interface MinioObjectClient {

    /**
     * 原子条件创建：同一 bucket/key 的并发调用必须至多一个返回 true。实现不得使用
     * 非原子的 HEAD-then-PUT；超时等不确定结果必须按 key 对账后再返回或抛出。
     */
    boolean putIfAbsent(String bucket, String key, InputStream stream, long length, String contentType);
}
