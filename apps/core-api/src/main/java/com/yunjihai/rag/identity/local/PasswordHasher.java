package com.yunjihai.rag.identity.local;

public interface PasswordHasher {
    String hash(char[] password);
    boolean matches(char[] password, String encoded);
}
