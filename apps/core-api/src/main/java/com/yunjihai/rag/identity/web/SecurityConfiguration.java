package com.yunjihai.rag.identity.web;

import com.yunjihai.rag.identity.SessionRevocationService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import java.time.Clock;

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
            SessionVersionFilter sessionVersionFilter) throws Exception {
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
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .addFilterAfter(sessionVersionFilter, SecurityContextHolderFilter.class);
        return http.build();
    }
}
