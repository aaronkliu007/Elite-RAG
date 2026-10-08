package com.yunjihai.rag.identity.local;

public final class UserAccountNotFoundException extends RuntimeException {
    public UserAccountNotFoundException() {
        super("用户不存在");
    }
}
