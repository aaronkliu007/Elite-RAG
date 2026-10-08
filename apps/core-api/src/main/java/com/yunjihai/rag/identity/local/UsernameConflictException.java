package com.yunjihai.rag.identity.local;

public final class UsernameConflictException extends RuntimeException {
    public UsernameConflictException() {
        super("用户名已存在");
    }

    public UsernameConflictException(Throwable cause) {
        super("用户名已存在", cause);
    }
}
