package com.yunjihai.rag.identity.local;

import java.text.Normalizer;
import java.util.Locale;

final class UsernameNormalizer {
    private UsernameNormalizer() {
    }

    static String normalize(String username) {
        if (username == null) {
            throw new LocalIdentityValidationException("用户名不能为空");
        }
        String normalized = Normalizer.normalize(username, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 255) {
            throw new LocalIdentityValidationException("用户名长度必须为 1 到 255 个字符");
        }
        return normalized;
    }
}
