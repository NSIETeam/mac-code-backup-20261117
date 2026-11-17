package cn.siyue.core;

/** Expected business rejection with a public error category. */
public final class BusinessException extends RuntimeException {
    private final ErrorCode code;
    /** Creates a rejection using a non-sensitive public explanation. */
    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }
    /** Returns the public category. */
    public ErrorCode code() { return code; }
}
