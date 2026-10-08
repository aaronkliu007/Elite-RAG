package com.yunjihai.rag.identity.local;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.CharBuffer;
import java.util.Objects;

public final class BCryptPasswordHasher implements PasswordHasher {
    private final BCryptPasswordEncoder encoder;

    public BCryptPasswordHasher() {
        this.encoder = new BCryptPasswordEncoder(12);
    }

    @Override
    public String hash(char[] password) {
        return encoder.encode(CharBuffer.wrap(Objects.requireNonNull(password)));
    }

    @Override
    public boolean matches(char[] password, String encoded) {
        return encoder.matches(CharBuffer.wrap(Objects.requireNonNull(password)), encoded);
    }
}
