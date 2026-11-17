package cn.siyue.core;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;

/** Maps errors to the contract and records system failure metadata without PII. */
@RestControllerAdvice
public class ApiExceptions {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptions.class);
    /** Returns a business rejection without internal stack details. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException error) {
        if (error.code() == ErrorCode.INTERNAL_ERROR) {
            LOG.error("core request failed category={}", error.getClass().getName());
            return response(ErrorCode.INTERNAL_ERROR, "服务暂时不可用");
        }
        return response(error.code(), error.getMessage());
    }
    /** Maps input failures without echoing rejected values. */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
        ConstraintViolationException.class, HandlerMethodValidationException.class,
        MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class,
        MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> invalid(Exception error) {
        return response(ErrorCode.INVALID_INPUT, "请求内容或参数无效");
    }
    /** Logs exception category only, as SQL/provider exception messages may contain PII. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> system(Exception error) {
        LOG.error("core request failed category={}", error.getClass().getName());
        return response(ErrorCode.INTERNAL_ERROR, "服务暂时不可用");
    }
    private ResponseEntity<ApiResponse<Void>> response(ErrorCode code, String message) {
        return ResponseEntity.status(code.status())
            .body(new ApiResponse<>(code.name(), message, null, MDC.get("traceId")));
    }
}
