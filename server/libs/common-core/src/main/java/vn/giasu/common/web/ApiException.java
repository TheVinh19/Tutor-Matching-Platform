package vn.giasu.common.web;

import org.springframework.http.HttpStatus;

/** Business error with an HTTP status and stable code, e.g. new ApiException(HttpStatus.CONFLICT, "SCHEDULE_CONFLICT", "..."). */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final transient Object details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Object details() { return details; }
}
