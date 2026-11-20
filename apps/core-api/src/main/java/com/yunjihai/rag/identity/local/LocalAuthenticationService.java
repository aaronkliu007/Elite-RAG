package com.yunjihai.rag.identity.local;

import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public class LocalAuthenticationService {
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final int MAX_FAILURES = 5;

    private final UserAccountStore accounts;
    private final LocalCredentialStore credentials;
    private final LoginSourceRateLimiter sourceRateLimiter;
    private final PasswordHasher passwordHasher;
    private final Clock clock;
    private final String dummyHash;
    private final com.yunjihai.rag.audit.AuditSink audit;

    public LocalAuthenticationService(
            UserAccountStore accounts,
            LocalCredentialStore credentials,
            LoginSourceRateLimiter sourceRateLimiter,
            PasswordHasher passwordHasher,
            Clock clock,
            String dummyHash) {
        this(accounts, credentials, sourceRateLimiter, passwordHasher, clock, dummyHash, com.yunjihai.rag.audit.AuditSink.noop());
    }

    public LocalAuthenticationService(UserAccountStore accounts, LocalCredentialStore credentials,
            LoginSourceRateLimiter sourceRateLimiter, PasswordHasher passwordHasher, Clock clock, String dummyHash,
            com.yunjihai.rag.audit.AuditSink audit) {
        this.accounts = Objects.requireNonNull(accounts);
        this.credentials = Objects.requireNonNull(credentials);
        this.sourceRateLimiter = Objects.requireNonNull(sourceRateLimiter);
        this.passwordHasher = Objects.requireNonNull(passwordHasher);
        this.clock = Objects.requireNonNull(clock);
        this.dummyHash = Objects.requireNonNull(dummyHash);
        this.audit = Objects.requireNonNull(audit);
    }

    @Transactional(noRollbackFor = AuthenticationRejectedException.class)
    public AuthenticationResult authenticate(String username, char[] password, String sourceIp) {
        Objects.requireNonNull(password);
        try {
            Instant now = clock.instant();
            sourceRateLimiter.assertAllowed(sourceIp, now);
            String normalizedUsername;
            try {
                normalizedUsername = UsernameNormalizer.normalize(username);
            } catch (LocalIdentityValidationException exception) {
                passwordHasher.matches(password, dummyHash);
                sourceRateLimiter.recordFailure(sourceIp, now);
                throw new AuthenticationRejectedException();
            }

            var credential = credentials.lockForAuthentication(normalizedUsername).orElse(null);
            if (credential == null) {
                passwordHasher.matches(password, dummyHash);
                sourceRateLimiter.recordFailure(sourceIp, now);
                throw new AuthenticationRejectedException();
            }

            boolean passwordMatches = passwordHasher.matches(password, credential.passwordHash());
            if (isLocked(credential, now) || !passwordMatches) {
                if (!isLocked(credential, now)) {
                    recordAccountFailure(credential, now);
                }
                sourceRateLimiter.recordFailure(sourceIp, now);
                throw new AuthenticationRejectedException();
            }

            UserAccount account = accounts.findById(credential.userId()).orElse(null);
            if (account == null || !account.enabled()) {
                sourceRateLimiter.recordFailure(sourceIp, now);
                throw new AuthenticationRejectedException();
            }
            credentials.resetAuthenticationState(account.id(), now);
            sourceRateLimiter.reset(sourceIp);
            return new AuthenticationResult(account.id(), account.username(), account.displayName(), credential.passwordChangeRequired());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private boolean isLocked(LocalCredential credential, Instant now) {
        return credential.lockedUntil() != null && now.isBefore(credential.lockedUntil());
    }

    @Transactional
    public void changeInitialPassword(java.util.UUID userId, String username, char[] currentPassword, char[] newPassword,
                                      com.yunjihai.rag.identity.SessionRevocationService sessions) {
        if (currentPassword == null || newPassword == null) {
            if (currentPassword != null) Arrays.fill(currentPassword, '\0');
            if (newPassword != null) Arrays.fill(newPassword, '\0');
            throw new LocalIdentityValidationException("当前密码和新密码不能为空");
        }
        try {
            var credential = credentials.lockForAuthentication(UsernameNormalizer.normalize(username))
                    .orElseThrow(AuthenticationRejectedException::new);
            if (!credential.userId().equals(userId) || !credential.passwordChangeRequired()
                    || !passwordHasher.matches(currentPassword, credential.passwordHash())) {
                throw new LocalIdentityValidationException("当前密码不正确或无需修改初始密码");
            }
            if (newPassword.length < 12 || newPassword.length > 128 || passwordHasher.matches(newPassword, credential.passwordHash())) {
                throw new LocalIdentityValidationException("新密码须为 12 到 128 个字符，且不能与原密码相同");
            }
            credentials.replacePassword(userId, passwordHasher.hash(newPassword), clock.instant());
            sessions.revokeAll(userId);
            audit.record(new com.yunjihai.rag.audit.AuditCommand(userId,
                    com.yunjihai.rag.audit.AuditAction.PASSWORD_CHANGE, "USER", userId,
                    com.yunjihai.rag.audit.AuditOutcome.SUCCEEDED, java.util.Map.of()));
        } finally {
            Arrays.fill(currentPassword, '\0');
            Arrays.fill(newPassword, '\0');
        }
    }

    private void recordAccountFailure(LocalCredential credential, Instant now) {
        boolean newWindow = credential.failureWindowStartedAt() == null
                || !now.isBefore(credential.failureWindowStartedAt().plus(FAILURE_WINDOW));
        int failures = newWindow ? 1 : credential.failedAttempts() + 1;
        Instant windowStartedAt = newWindow ? now : credential.failureWindowStartedAt();
        Instant lockedUntil = failures >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null;
        credentials.updateAuthenticationState(
                credential.userId(), failures, windowStartedAt, lockedUntil, now);
    }
}
