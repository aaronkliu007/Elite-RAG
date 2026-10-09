package com.yunjihai.rag.knowledge.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.api.ApiExceptionHandler;
import com.yunjihai.rag.knowledge.DocumentCreationAuthorization;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Authorizes upload routes before DispatcherServlet triggers multipart parsing. */
public final class UploadAuthorizationFilter extends OncePerRequestFilter {
    private static final Pattern UPLOAD_PATH = Pattern.compile(
            "^/api/v1/knowledge-spaces/([0-9a-fA-F-]{36})/documents/?$");
    private final DocumentCreationAuthorization authorization;
    private final ObjectMapper json;

    public UploadAuthorizationFilter(
            DocumentCreationAuthorization authorization,
            ObjectMapper json) {
        this.authorization = Objects.requireNonNull(authorization);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var match = UPLOAD_PATH.matcher(applicationPath(request));
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !match.matches()) {
            filterChain.doFilter(request, response);
            return;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            UUID actorId = UUID.fromString(authentication.getName());
            UUID spaceId = UUID.fromString(match.group(1));
            authorization.require(actorId, spaceId);
            filterChain.doFilter(request, response);
        } catch (AccessDeniedAsNotFoundException | IllegalArgumentException denied) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), ApiExceptionHandler.error(
                    "NOT_FOUND", "资源不存在或无权访问", request, Map.of()));
        }
    }

    private static String applicationPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
    }
}
