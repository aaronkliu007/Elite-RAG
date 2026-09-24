package com.yunjihai.rag.identity;

public final class DisabledUserSessionException extends RuntimeException {

    public DisabledUserSessionException() {
        super("会话已失效");
    }
}
