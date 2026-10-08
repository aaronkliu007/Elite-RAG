package com.yunjihai.rag.identity.local;

public final class AuthenticationRejectedException extends RuntimeException {
    public AuthenticationRejectedException() {
        super("用户名或密码错误");
    }
}
