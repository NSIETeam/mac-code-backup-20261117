package cn.siyue.core;

import org.slf4j.MDC;

/** Stable REST envelope without leaking database or provider errors. */
public record ApiResponse<T>(String code, String message, T data, String traceId) {
    /** Wraps a successful payload and the server-generated trace identifier. */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "成功", data, MDC.get("traceId"));
    }
}
