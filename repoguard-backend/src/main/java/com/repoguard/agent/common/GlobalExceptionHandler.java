package com.repoguard.agent.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String INTERNAL_ERROR_MESSAGE = "系统内部异常，请联系管理员。";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        HttpStatus status = switch (exception.getErrorCode()) {
            case TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
            .body(ApiResponse.error(exception.getErrorCode(), exception.getMessage()));
    }

    @ExceptionHandler({
        BindException.class,
        ConstraintViolationException.class,
        MethodArgumentNotValidException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleValidationException(Exception exception) {
        return ResponseEntity.badRequest()
            .body(ApiResponse.error(ErrorCode.BAD_REQUEST, exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        LOGGER.error(
            "Unhandled application exception type={} message={} location={}",
            exception.getClass().getName(),
            sanitizeLogMessage(exception.getMessage()),
            topStackLocation(exception)
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE));
    }

    private String sanitizeLogMessage(String message) {
        if (message == null || message.isBlank()) {
            return "<empty>";
        }
        String sanitized = message
            .replaceAll("(?i)(token|api[-_ ]?key|password|secret)=([^\\s,;]+)", "$1=****")
            .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1****");
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
}
