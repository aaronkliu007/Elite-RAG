package com.yunjihai.rag.ingestion;

import java.io.InputStream;

public interface MinioObjectClient {

    /**
     * 原子条件创建：同一 bucket/key 的并发调用必须至多一个返回 true。实现不得使用
     * 非原子的 HEAD-then-PUT；只有服务端明确返回的条件冲突可以在严格对账后返回 false，
     * 超时或响应丢失等不确定结果必须抛出固定安全错误。
     */
    boolean putIfAbsent(String bucket, String key, InputStream stream, long length, String contentType);
}
