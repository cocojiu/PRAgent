package com.repoguard.agent.common;

import jakarta.validation.ConstraintViolationException;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Pattern SENSITIVE_ASSIGNMENT_PATTERN = Pattern.compile(
        "(?i)([\"']?\\b[\\w.-]*(?:token|password|secret|api[-_ ]?key)[\\w.-]*\\b[\"']?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;}]+)"
    );
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final String VALIDATION_ERROR_MESSAGE = "Request validation failed";
    private static final String INTERNAL_ERROR_MESSAGE = "系统内部异常，请联系管理员。";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        HttpStatus status = switch (exception.getErrorCode()) {
            case TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
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
        String sanitized = SENSITIVE_ASSIGNMENT_PATTERN.matcher(message).replaceAll(GlobalExceptionHandler::maskSensitiveValue);
        sanitized = BEARER_TOKEN_PATTERN.matcher(sanitized).replaceAll("$1****");
        return sanitized.length() > 300 ? sanitized.substring(0, 297) + "..." : sanitized;
    }

    private static String maskSensitiveValue(MatchResult match) {
        String value = match.group(2);
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return match.group(1) + "\"****\"";
        }
        if (value.startsWith("'") && value.endsWith("'")) {
            return match.group(1) + "'****'";
        }
        return match.group(1) + "****";
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
