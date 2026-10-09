package com.yunjihai.rag.identity.local;

import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.common.UuidV7;
import com.yunjihai.rag.identity.SessionRevocationService;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class UserAdministrationService {
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 128;

    private final UserAccountStore accounts;
    private final LocalCredentialStore credentials;
    private final PasswordHasher passwordHasher;
    private final SessionRevocationService sessions;
    private final AuditSink auditSink;
    private final Clock clock;
    private final SystemAdministratorDisableProtection administratorProtection;

    public UserAdministrationService(
            UserAccountStore accounts,
            LocalCredentialStore credentials,
            PasswordHasher passwordHasher,
            SessionRevocationService sessions,
            AuditSink auditSink,
            Clock clock) {
        this(accounts, credentials, passwordHasher, sessions, auditSink, clock,
                SystemAdministratorDisableProtection.allowAll());
    }

    public UserAdministrationService(
            UserAccountStore accounts,
            LocalCredentialStore credentials,
            PasswordHasher passwordHasher,
            SessionRevocationService sessions,
            AuditSink auditSink,
            Clock clock,
            SystemAdministratorDisableProtection administratorProtection) {
        this.accounts = Objects.requireNonNull(accounts);
        this.credentials = Objects.requireNonNull(credentials);
        this.passwordHasher = Objects.requireNonNull(passwordHasher);
        this.sessions = Objects.requireNonNull(sessions);
        this.auditSink = Objects.requireNonNull(auditSink);
        this.clock = Objects.requireNonNull(clock);
        this.administratorProtection = Objects.requireNonNull(administratorProtection);
    }

    @Transactional
    public UserAccount createUser(String username, String displayName, char[] password, UUID actorId) {
        Objects.requireNonNull(password);
        try {
            String normalizedUsername = UsernameNormalizer.normalize(username);
            String normalizedDisplayName = normalizeDisplayName(displayName);
            validatePassword(password);
            Instant now = clock.instant();
            UserAccount account = new UserAccount(
                    UuidV7.generate(now), normalizedUsername, normalizedDisplayName, true, 0, now, now);
            accounts.insert(account, "LOCAL", normalizedUsername);
            credentials.insert(new LocalCredential(
                    account.id(), normalizedUsername, passwordHasher.hash(password), 0, null, null, now));
            audit(actorId, AuditAction.USER_CREATE, account.id(), Map.of());
            return account;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Transactional
    public void setEnabled(UUID userId, boolean enabled, UUID actorId) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(actorId);
        Instant now = clock.instant();
        if (accounts.findById(userId).isEmpty()) {
            throw new UserAccountNotFoundException();
        }
        if (!enabled && userId.equals(actorId)) {
            throw new LocalIdentityValidationException("不能停用当前登录用户");
        }
        if (!enabled) administratorProtection.requireCanDisable(userId, actorId);
        accounts.setEnabled(userId, enabled, now);
        sessions.setEnabled(userId, enabled);
        audit(actorId, enabled ? AuditAction.USER_ENABLE : AuditAction.USER_DISABLE, userId, Map.of());
    }

    @Transactional
    public void resetPassword(UUID userId, char[] password, UUID actorId) {
        Objects.requireNonNull(password);
        try {
            if (accounts.findById(userId).isEmpty()) {
                throw new UserAccountNotFoundException();
            }
            validatePassword(password);
            Instant now = clock.instant();
            credentials.replacePassword(userId, passwordHasher.hash(password), now);
            sessions.revokeAll(userId);
            audit(actorId, AuditAction.PASSWORD_RESET, userId, Map.of());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Transactional(readOnly = true)
    public List<UserAccount> listUsers() {
        return List.copyOf(accounts.list());
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new LocalIdentityValidationException("显示名称不能为空");
        }
        String normalized = displayName.strip();
        if (normalized.length() > 255) {
            throw new LocalIdentityValidationException("显示名称不能超过 255 个字符");
        }
        return normalized;
    }

    private static void validatePassword(char[] password) {
        if (password.length < MIN_PASSWORD_LENGTH || password.length > MAX_PASSWORD_LENGTH) {
            throw new LocalIdentityValidationException("密码长度必须为 12 到 128 个字符");
        }
    }

    private void audit(UUID actorId, AuditAction action, UUID userId, Map<String, String> metadata) {
        auditSink.record(new AuditCommand(actorId, action, "USER", userId, AuditOutcome.SUCCEEDED, metadata));
    }
}
