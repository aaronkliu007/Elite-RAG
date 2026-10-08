package com.yunjihai.rag.identity.web;

import com.yunjihai.rag.identity.AuthenticatedSession;
import com.yunjihai.rag.identity.DisabledUserSessionException;
import com.yunjihai.rag.identity.SessionRevocationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;

public final class SessionVersionFilter extends OncePerRequestFilter {
    private final SessionRevocationService sessions;
    private final Clock clock;
    private final SessionProperties properties;

    public SessionVersionFilter(
            SessionRevocationService sessions,
            Clock clock,
            SessionProperties properties) {
        this.sessions = Objects.requireNonNull(sessions);
        this.clock = Objects.requireNonNull(clock);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof SessionPrincipal principal) {
            try {
                sessions.assertActive(new AuthenticatedSession(principal.userId(), principal.sessionVersion()));
                if (!clock.instant().isBefore(principal.issuedAt().plus(properties.getAbsoluteTimeout()))) {
                    reject(request, response);
                    return;
                }
            } catch (DisabledUserSessionException exception) {
                reject(request, response);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
