package com.yunjihai.rag.identity.web;

import com.yunjihai.rag.identity.SessionRevocationService;
import com.yunjihai.rag.identity.local.LocalAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/session")
@ConditionalOnProperty(prefix = "rag.session", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionController {
    private final LocalAuthenticationService authenticationService;
    private final SessionRevocationService sessions;
    private final SecurityContextRepository securityContexts;
    private final Clock clock;

    public SessionController(
            LocalAuthenticationService authenticationService,
            SessionRevocationService sessions,
            SecurityContextRepository securityContexts,
            Clock clock) {
        this.authenticationService = Objects.requireNonNull(authenticationService);
        this.sessions = Objects.requireNonNull(sessions);
        this.securityContexts = Objects.requireNonNull(securityContexts);
        this.clock = Objects.requireNonNull(clock);
    }

    @PostMapping("/login")
    public SessionView login(
            @RequestBody LoginRequest command,
            HttpServletRequest request,
            HttpServletResponse response) {
        Objects.requireNonNull(command);
        var result = authenticationService.authenticate(
                command.username(), command.password(), request.getRemoteAddr());
        var issued = sessions.issue(result.userId());
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        var principal = new SessionPrincipal(
                result.userId(), result.username(), result.displayName(), issued.version(), clock.instant(), result.passwordChangeRequired());
        Authentication authenticated = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);
        return SessionView.from(principal);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    @PostMapping("/change-initial-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeInitialPassword(@RequestBody ChangePasswordRequest command, Authentication authentication,
                                      HttpServletRequest request) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionPrincipal principal)
                || !principal.passwordChangeRequired()) {
            throw new org.springframework.security.access.AccessDeniedException("无需首次修改密码");
        }
        authenticationService.changeInitialPassword(principal.userId(), principal.username(),
                command.currentPassword(), command.newPassword(), sessions);
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
    }

    @GetMapping("/me")
    public SessionView me(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                    "未登录");
        }
        return SessionView.from(principal);
    }

    @GetMapping("/csrf")
    public CsrfView csrf(CsrfToken token) {
        return new CsrfView(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    public record LoginRequest(String username, char[] password) {
    }

    public record ChangePasswordRequest(char[] currentPassword, char[] newPassword) { }

    public record SessionView(UUID userId, String username, String displayName, boolean passwordChangeRequired) {
        private static SessionView from(SessionPrincipal principal) {
            return new SessionView(principal.userId(), principal.username(), principal.displayName(), principal.passwordChangeRequired());
        }
    }

    public record CsrfView(String headerName, String parameterName, String token) {
    }
}
