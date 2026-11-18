package vn.giasu.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException ex, HttpServletRequest req) {
        return body(ex.status(), ApiError.of(req, ex.code(), ex.getMessage(), ex.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<Map<String, String>> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        return body(HttpStatus.BAD_REQUEST, ApiError.of(req, "VALIDATION_FAILED", "Dữ liệu không hợp lệ", fields));
    }

    /** e.g. ?mode=XYZ or a non-UUID path id. */
    @ExceptionHandler(TypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(TypeMismatchException ex, HttpServletRequest req) {
        String name = ex.getPropertyName() != null ? ex.getPropertyName() : "value";
        return body(HttpStatus.BAD_REQUEST, ApiError.of(req, "VALIDATION_FAILED", "Dữ liệu không hợp lệ",
                List.of(Map.of("field", name, "message", "sai định dạng"))));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpServletRequest req) {
        return body(HttpStatus.BAD_REQUEST, ApiError.of(req, "MALFORMED_REQUEST", "Không đọc được nội dung yêu cầu", null));
    }

    // Method security throws inside MVC, so these would otherwise fall into the 500 handler.
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(HttpServletRequest req) {
        return body(HttpStatus.FORBIDDEN, ApiError.of(req, "FORBIDDEN", "Không có quyền thực hiện thao tác này", null));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> unauthenticated(HttpServletRequest req) {
        return body(HttpStatus.UNAUTHORIZED, ApiError.of(req, "UNAUTHORIZED", "Chưa đăng nhập hoặc token không hợp lệ", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest req) {
        // Spring MVC built-ins (404 no resource, 405 method, 415 media type...) implement ErrorResponse.
        if (ex instanceof ErrorResponse framework) {
            HttpStatusCode status = framework.getStatusCode();
            String code = switch (status.value()) { case 404 -> "NOT_FOUND"; case 400 -> "VALIDATION_FAILED"; default -> "HTTP_" + status.value(); };
            return body(status, ApiError.of(req, code, framework.getBody().getTitle(), null));
        }
        ApiError error = ApiError.of(req, "INTERNAL_ERROR", "Lỗi hệ thống", null);
        log.error("Unhandled error requestId={}", error.requestId(), ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, error);
    }

    private static ResponseEntity<ApiError> body(HttpStatusCode status, ApiError error) {
        return ResponseEntity.status(status).header(ApiError.REQUEST_ID_HEADER, error.requestId()).body(error);
    }
}
