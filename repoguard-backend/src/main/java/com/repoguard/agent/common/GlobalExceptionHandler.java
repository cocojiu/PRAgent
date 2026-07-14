package com.repoguard.agent.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String VALIDATION_ERROR_MESSAGE = "Request validation failed";
    private static final String UNSUPPORTED_MEDIA_TYPE_MESSAGE = "Content type is not supported";
    private static final String INTERNAL_ERROR_MESSAGE = "系统内部异常，请联系管理员。";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        HttpStatus status = switch (exception.getErrorCode()) {
            case TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case PAYLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
            .body(ApiResponse.error(exception.getErrorCode(), exception.getMessage()));
    }

    @ExceptionHandler({
        BindException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleValidationException(Exception exception) {
        return ResponseEntity.badRequest()
            .body(ApiResponse.error(ErrorCode.BAD_REQUEST, VALIDATION_ERROR_MESSAGE));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(ApiResponse.error(ErrorCode.BAD_REQUEST, UNSUPPORTED_MEDIA_TYPE_MESSAGE));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        LOGGER.error(
            "Unhandled application exception traceId={} type={} message={} location={}",
            traceId(),
            exception.getClass().getName(),
            sanitizeLogMessage(exception.getMessage()),
            topStackLocation(exception)
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE));
    }

    String sanitizeLogMessage(String message) {
        if (message == null || message.isBlank()) {
            return "<empty>";
        }
        String sanitized = SensitiveTextSanitizer.sanitize(message);
        return sanitized.length() > 300 ? sanitized.substring(0, 297) + "..." : sanitized;
    }

    private String topStackLocation(Exception exception) {
        StackTraceElement[] stackTrace = exception.getStackTrace();
        if (stackTrace == null || stackTrace.length == 0) {
            return "<unknown>";
        }
        StackTraceElement first = stackTrace[0];
        return first.getClassName() + "#" + first.getMethodName() + ":" + first.getLineNumber();
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? "<none>" : traceId;
    }
}
