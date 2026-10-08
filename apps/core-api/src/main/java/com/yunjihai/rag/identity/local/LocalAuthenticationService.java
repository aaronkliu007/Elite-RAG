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

    public LocalAuthenticationService(
            UserAccountStore accounts,
            LocalCredentialStore credentials,
            LoginSourceRateLimiter sourceRateLimiter,
            PasswordHasher passwordHasher,
            Clock clock,
            String dummyHash) {
        this.accounts = Objects.requireNonNull(accounts);
        this.credentials = Objects.requireNonNull(credentials);
        this.sourceRateLimiter = Objects.requireNonNull(sourceRateLimiter);
        this.passwordHasher = Objects.requireNonNull(passwordHasher);
        this.clock = Objects.requireNonNull(clock);
        this.dummyHash = Objects.requireNonNull(dummyHash);
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
            return new AuthenticationResult(account.id(), account.username(), account.displayName());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private boolean isLocked(LocalCredential credential, Instant now) {
        return credential.lockedUntil() != null && now.isBefore(credential.lockedUntil());
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
