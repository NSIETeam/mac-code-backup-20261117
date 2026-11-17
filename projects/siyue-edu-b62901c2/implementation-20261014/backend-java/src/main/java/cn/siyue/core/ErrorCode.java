package cn.siyue.core;

import org.springframework.http.HttpStatus;

/** Public error categories; details never contain personal data. */
public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST), UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN), PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN), NOT_FOUND(HttpStatus.NOT_FOUND),
    ORIGIN_REJECTED(HttpStatus.FORBIDDEN), IDENTITY_VERIFICATION_REQUIRED(HttpStatus.FORBIDDEN),
    PAYMENT_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT), PREVIEW_STALE(HttpStatus.CONFLICT), VERSION_CONFLICT(HttpStatus.CONFLICT),
    ALREADY_EXISTS(HttpStatus.CONFLICT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    CONFIGURATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE), SEARCH_INDEX_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);
    private final HttpStatus status;
    ErrorCode(HttpStatus status) { this.status = status; }
    /** Returns the category's HTTP status. */
    public HttpStatus status() { return status; }
}
