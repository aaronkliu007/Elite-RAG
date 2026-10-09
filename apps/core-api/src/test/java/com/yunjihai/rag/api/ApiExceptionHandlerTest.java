package com.yunjihai.rag.api;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.identity.local.UsernameConflictException;
import com.yunjihai.rag.infrastructure.postgres.PostgresPersistenceExceptionTranslator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailureController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new RequestTraceFilter())
            .build();

    @Test
    void mapsStable400401403404409And503Envelopes() throws Exception {
        assertError("/failure/bad", 400, "BAD_REQUEST", "请求参数不正确");
        assertError("/failure/unauthorized", 401, "UNAUTHORIZED", "请先登录");
        assertError("/failure/forbidden", 403, "FORBIDDEN", "无权执行此操作");
        assertError("/failure/hidden", 404, "NOT_FOUND", "资源不存在或无权访问");
        assertError("/failure/conflict", 409, "CONFLICT", "请求与当前状态冲突");
        assertError("/failure/unavailable", 503, "SERVICE_UNAVAILABLE", "服务暂时不可用");
        assertError("/failure/missing-part", 400, "BAD_REQUEST", "请求参数不正确");
        assertError("/failure/upload-too-large", 413, "UPLOAD_TOO_LARGE", "上传文件超过大小限制");
    }

    @Test
    void preservesValidRequestIdAndNeverLeaksInternalFailureDetails() throws Exception {
        String requestId = "018f25c8-5f7a-7abc-8def-0123456789ab";
        mvc.perform(get("/failure/unavailable").header(RequestTraceFilter.HEADER_NAME, requestId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(RequestTraceFilter.HEADER_NAME, requestId))
                .andExpect(jsonPath("$.traceId").value(requestId))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("select *")
                            .doesNotContain("jdbc:postgresql")
                            .doesNotContain("internal-db")
                            .doesNotContain("DataAccessResourceFailureException")
                            .doesNotContain("at com.");
                });
    }

    @Test
    void replacesInvalidRequestIdWithUuidV7() throws Exception {
        var result = mvc.perform(get("/failure/hidden")
                        .header(RequestTraceFilter.HEADER_NAME, "not-a-uuid"))
                .andExpect(status().isNotFound())
                .andReturn();

        String traceId = result.getResponse().getHeader(RequestTraceFilter.HEADER_NAME);
        assertThat(traceId).isNotBlank();
        assertThat(UUID.fromString(traceId).version()).isEqualTo(7);
        assertThat(result.getResponse().getContentAsString()).contains(traceId);
    }

    @Test
    void missingRequiredHeaderUsesStableBadRequestEnvelope() throws Exception {
        mvc.perform(post("/failure/required-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("请求参数不正确"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    private void assertError(String path, int statusCode, String code, String message) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").isMap());
    }

    @RestController
    static class FailureController {
        @GetMapping("/failure/bad")
        void bad() { throw new IllegalArgumentException("select * from secret"); }

        @GetMapping("/failure/unauthorized")
        void unauthorized() { throw new AuthenticationCredentialsNotFoundException("secret"); }

        @GetMapping("/failure/forbidden")
        void forbidden() { throw new AccessDeniedException("secret"); }

        @GetMapping("/failure/hidden")
        void hidden() { throw new AccessDeniedAsNotFoundException(); }

        @GetMapping("/failure/conflict")
        void conflict() { throw new UsernameConflictException(); }

        @GetMapping("/failure/unavailable")
        void unavailable() {
            throw PostgresPersistenceExceptionTranslator.translate(
                    new DataAccessResourceFailureException(
                            "jdbc:postgresql://internal-db/rag select * from secret"));
        }


        @PostMapping("/failure/required-header")
        void requiredHeader(@RequestHeader("Idempotency-Key") String ignored) { }

        @GetMapping("/failure/missing-part")
        void missingPart() throws MissingServletRequestPartException {
            throw new MissingServletRequestPartException("file");
        }

        @GetMapping("/failure/upload-too-large")
        void uploadTooLarge() { throw new MaxUploadSizeExceededException(100L * 1024 * 1024); }
    }
}
