package com.yunjihai.rag.identity.local;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rag.bootstrap-admin")
public record BootstrapAdminProperties(String username, String password, String displayName) {
    public BootstrapAdminProperties {
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        displayName = displayName == null ? "" : displayName;
    }

    boolean complete() {
        return !username.isBlank() && !password.isBlank() && !displayName.isBlank();
    }
}
