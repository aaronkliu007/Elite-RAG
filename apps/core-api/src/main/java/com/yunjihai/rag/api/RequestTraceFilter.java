package com.yunjihai.rag.api;

import com.yunjihai.rag.common.UuidV7;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestTraceFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Request-Id";
    public static final String ATTRIBUTE_NAME = RequestTraceFilter.class.getName() + ".traceId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = validUuid(request.getHeader(HEADER_NAME));
        if (traceId == null) {
            traceId = UuidV7.generate(Instant.now()).toString();
        }
        request.setAttribute(ATTRIBUTE_NAME, traceId);
        response.setHeader(HEADER_NAME, traceId);
        filterChain.doFilter(request, response);
    }

    public static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE_NAME);
        if (value instanceof String traceId && validUuid(traceId) != null) {
            return traceId;
        }
        return UuidV7.generate(Instant.now()).toString();
    }

    private static String validUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equalsIgnoreCase(value) ? parsed.toString() : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
