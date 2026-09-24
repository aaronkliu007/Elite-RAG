package com.yunjihai.rag.ingestion;

import org.springframework.http.MediaType;

import java.io.InputStream;

public interface BlobStore {

    StoredBlob putIfAbsent(String sha256, InputStream stream, long length, MediaType mediaType);

    /**
     * 登记延迟清理候选。实现不得在请求线程中直接删除内容寻址对象；后台回收器必须在
     * 数据库中原子确认不存在已提交引用和上传预留后才能删除。
     */
    void scheduleCleanup(StoredBlob blob);
}
