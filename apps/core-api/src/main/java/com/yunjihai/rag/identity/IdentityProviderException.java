package com.yunjihai.rag.identity;

public final class IdentityProviderException extends RuntimeException {

    private final IdentityProviderFailureKind kind;

    public IdentityProviderException(IdentityProviderFailureKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public IdentityProviderException(IdentityProviderFailureKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public IdentityProviderFailureKind kind() {
        return kind;
    }
}
