package com.yunjihai.rag.identity.web;

import com.yunjihai.rag.api.ApiExceptionHandler;
import com.yunjihai.rag.identity.SessionRevocationService;
import com.yunjihai.rag.knowledge.DocumentCreationAuthorization;
import com.yunjihai.rag.knowledge.web.UploadAuthorizationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.session", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(SessionProperties.class)
public class SecurityConfiguration {

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SessionVersionFilter sessionVersionFilter(
            SessionRevocationService sessions,
            Clock clock,
            SessionProperties properties) {
        return new SessionVersionFilter(sessions, clock, properties);
    }

    @Bean
    SecurityFilterChain sessionSecurityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContexts,
            SessionVersionFilter sessionVersionFilter,
            ObjectProvider<DocumentCreationAuthorization> documentCreation,
            ObjectMapper json) throws Exception {
        var csrfTokens = new HttpSessionCsrfTokenRepository();
        csrfTokens.setHeaderName("X-CSRF-TOKEN");
        http
                .securityContext(context -> context
                        .securityContextRepository(securityContexts)
                        .requireExplicitSave(true))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/session/login",
                                "/api/v1/session/csrf",
                                "/actuator/health/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) ->
                                writeError(request, response, json,
                                        HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHORIZED", "请先登录"))
                        .accessDeniedHandler((request, response, failure) ->
                                writeError(request, response, json,
                                        HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN", "无权执行此操作")))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .addFilterAfter(sessionVersionFilter, SecurityContextHolderFilter.class)
                .addFilterAfter(new UploadAuthorizationFilter(
                        documentCreation.getIfAvailable(DocumentCreationAuthorization::denyAll), json),
                        SessionVersionFilter.class);
        return http.build();
    }

    private static void writeError(
            HttpServletRequest request,
            HttpServletResponse response,
            ObjectMapper json,
            int status,
            String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ApiExceptionHandler.error(code, message, request, Map.of()));
    }
}
