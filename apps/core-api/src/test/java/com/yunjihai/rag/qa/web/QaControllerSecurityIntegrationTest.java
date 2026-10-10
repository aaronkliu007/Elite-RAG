package com.yunjihai.rag.qa.web;

import com.yunjihai.rag.api.ApiExceptionHandler;
import com.yunjihai.rag.api.RequestTraceFilter;
import com.yunjihai.rag.identity.*;
import com.yunjihai.rag.identity.web.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.*;
import java.util.List;

import static com.yunjihai.rag.qa.web.QaTestHarness.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        classes=QaControllerSecurityIntegrationTest.Config.class,
        properties={
                "rag.session.enabled=true",
                "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"
        })
@AutoConfigureMockMvc
class QaControllerSecurityIntegrationTest {
    @org.springframework.beans.factory.annotation.Autowired MockMvc mvc;

    @Test
    void requiresSessionCsrfIdempotencyHeaderAndQuestionOnlyBody() throws Exception {
        mvc.perform(get("/api/qa/questions/"+id(999)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mvc.perform(post("/api/qa/questions")
                        .with(authentication(sessionAuthentication()))
                        .contentType("application/json")
                        .header("Idempotency-Key","missing-csrf")
                        .content("{\"question\":\"制度何时生效？\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/qa/questions")
                        .with(authentication(sessionAuthentication())).with(csrf())
                        .contentType("application/json")
                        .content("{\"question\":\"制度何时生效？\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        mvc.perform(post("/api/qa/questions")
                        .with(authentication(sessionAuthentication())).with(csrf())
                        .contentType("application/json")
                        .header("Idempotency-Key","unknown-field")
                        .content("{\"question\":\"制度何时生效？\",\"actorId\":\""+id(9)+"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        mvc.perform(post("/api/qa/questions")
                        .with(authentication(sessionAuthentication())).with(csrf())
                        .contentType("application/json")
                        .header("Idempotency-Key","valid")
                        .content("{\"question\":\"制度何时生效？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.citations[0].evidenceIds[0]").value(id(100).toString()))
                .andExpect(jsonPath("$.citations[0].quote").doesNotExist());
    }

    private static UsernamePasswordAuthenticationToken sessionAuthentication() {
        var principal=new SessionPrincipal(ACTOR,"alice","Alice",0,NOW);
        return UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude=DataSourceAutoConfiguration.class)
    @Import({SecurityConfiguration.class,ApiExceptionHandler.class,RequestTraceFilter.class})
    static class Config {
        @Bean Clock clock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
        @Bean InMemorySessionVersionStore versions(){return new InMemorySessionVersionStore();}
        @Bean SessionRevocationService sessions(InMemorySessionVersionStore versions){
            return new SessionRevocationService(versions);
        }
        @Bean QaController qaController(){return new QaTestHarness(Mode.ANSWERED).controller;}
    }
}
