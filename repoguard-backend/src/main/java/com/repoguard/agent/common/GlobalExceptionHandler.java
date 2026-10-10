package com.repoguard.agent.common;

import jakarta.validation.ConstraintViolationException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    public static final String ERROR_ID_HEADER = "X-Error-Id";

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR_ID_MDC = "errorId";
    private static final String VALIDATION_ERROR_MESSAGE = "Request validation failed";
    private static final String UNSUPPORTED_MEDIA_TYPE_MESSAGE = "Content type is not supported";
    private static final String INTERNAL_ERROR_MESSAGE = "系统内部异常，请联系管理员。";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        HttpStatus status = switch (exception.getErrorCode()) {
            case TASK_NOT_FOUND, RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case PAYLOAD_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return error(status, exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler({BindException.class, ConstraintViolationException.class,
        HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
        MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidationException(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, VALIDATION_ERROR_MESSAGE);
    }

    @ExceptionHandler({HttpMediaTypeNotSupportedException.class, HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeException(Exception exception) {
        boolean unsupported = exception instanceof HttpMediaTypeNotSupportedException;
        return error(unsupported ? HttpStatus.UNSUPPORTED_MEDIA_TYPE : HttpStatus.NOT_ACCEPTABLE,
            ErrorCode.BAD_REQUEST, unsupported ? UNSUPPORTED_MEDIA_TYPE_MESSAGE : "Requested response type is not supported");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        String errorId = UUID.randomUUID().toString();
        logUnhandledException(errorId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_JSON)
            .header(ERROR_ID_HEADER, errorId)
            .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(Exception exception) {
        return error(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Requested resource not found");
    }

    private ResponseEntity<ApiResponse<Void>> error(HttpStatus status, ErrorCode code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error(code, message));
    }

    private void logUnhandledException(String errorId, Exception exception) {
        String previousErrorId = MDC.get(ERROR_ID_MDC);
        MDC.put(ERROR_ID_MDC, errorId);
        try {
            LOGGER.error(
                "Unhandled application exception traceId={} errorId={} type={} message={} location={}",
                traceId(), errorId, exception.getClass().getName(),
                sanitizeLogMessage(exception.getMessage()), topStackLocation(exception), exception
            );
        } finally {
            restoreErrorId(previousErrorId);
        }
    }

    private void restoreErrorId(String previousErrorId) {
        if (previousErrorId == null) {
            MDC.remove(ERROR_ID_MDC);
            return;
        }
        MDC.put(ERROR_ID_MDC, previousErrorId);
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
