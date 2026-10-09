package com.yunjihai.rag.api;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.api.idempotency.IdempotencyConflictException;
import com.yunjihai.rag.identity.local.AuthenticationRejectedException;
import com.yunjihai.rag.identity.local.LocalIdentityValidationException;
import com.yunjihai.rag.identity.local.UsernameConflictException;
import com.yunjihai.rag.infrastructure.postgres.PostgresPersistenceExceptionTranslator;
import com.yunjihai.rag.ingestion.IngestionLeaseConflictException;
import com.yunjihai.rag.ingestion.IngestionRequestConflictException;
import com.yunjihai.rag.ingestion.UploadRejectedException;
import com.yunjihai.rag.ingestion.UploadConflictException;
import com.yunjihai.rag.knowledge.DocumentLifecycleException;
import com.yunjihai.rag.knowledge.KnowledgeNotFoundException;
import com.yunjihai.rag.retrieval.RetrievalAuthorizationUnavailableException;
import com.yunjihai.rag.retrieval.search.RetrievalUnavailableException;
import com.yunjihai.rag.solution.generation.GenerationRequestConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({
            IllegalArgumentException.class,
            LocalIdentityValidationException.class,
            UploadRejectedException.class,
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class,
            HandlerMethodValidationException.class
    })
    ResponseEntity<ApiError> badRequest(Exception failure, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "请求参数不正确", request, Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge(Exception failure, HttpServletRequest request) {
        return response(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "UPLOAD_TOO_LARGE",
                "上传文件超过大小限制",
                request,
                Map.of());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    ResponseEntity<ApiError> validation(Exception failure, HttpServletRequest request) {
        var fields = new LinkedHashMap<String, String>();
        var binding = failure instanceof MethodArgumentNotValidException invalid
                ? invalid.getBindingResult()
                : ((BindException) failure).getBindingResult();
        binding.getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), safeValidationMessage(error.getDefaultMessage())));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数校验失败", request, fields);
    }

    @ExceptionHandler({AuthenticationException.class, AuthenticationRejectedException.class})
    ResponseEntity<ApiError> unauthorized(Exception failure, HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录", request, Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(AccessDeniedException failure, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权执行此操作", request, Map.of());
    }

    @ExceptionHandler({AccessDeniedAsNotFoundException.class, KnowledgeNotFoundException.class})
    ResponseEntity<ApiError> notFound(Exception failure, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "资源不存在或无权访问", request, Map.of());
    }

    @ExceptionHandler({
            UsernameConflictException.class,
            DocumentLifecycleException.class,
            GenerationRequestConflictException.class,
            IngestionRequestConflictException.class,
            IngestionLeaseConflictException.class,
            UploadConflictException.class,
            IdempotencyConflictException.class
    })
    ResponseEntity<ApiError> conflict(Exception failure, HttpServletRequest request) {
        String code = failure instanceof IdempotencyConflictException conflict
                ? conflict.code()
                : "CONFLICT";
        String message = failure instanceof IdempotencyConflictException conflict
                ? conflict.publicMessage()
                : "请求与当前状态冲突";
        return response(HttpStatus.CONFLICT, code, message, request, Map.of());
    }

    @ExceptionHandler(PostgresPersistenceExceptionTranslator.Failure.class)
    ResponseEntity<ApiError> unavailable(
            PostgresPersistenceExceptionTranslator.Failure failure,
            HttpServletRequest request) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE",
                "服务暂时不可用",
                request,
                Map.of());
    }

    @ExceptionHandler({RetrievalUnavailableException.class, RetrievalAuthorizationUnavailableException.class})
    ResponseEntity<ApiError> retrievalUnavailable(Exception failure, HttpServletRequest request) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "RETRIEVAL_UNAVAILABLE",
                "检索服务暂时不可用",
                request,
                Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> internal(Exception failure, HttpServletRequest request) {
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "服务器处理请求失败",
                request,
                Map.of());
    }

    public static ApiError error(
            String code, String message, HttpServletRequest request, Map<String, String> fieldErrors) {
        return new ApiError(code, message, RequestTraceFilter.traceId(request), fieldErrors);
    }

    private static ResponseEntity<ApiError> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, String> fieldErrors) {
        return ResponseEntity.status(status).body(error(code, message, request, fieldErrors));
    }

    private static String safeValidationMessage(String message) {
        return message == null || message.isBlank() ? "格式不正确" : message;
    }
}
