package vn.giasu.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/** Standard error body {code,message,details,requestId}; never carries stack traces. */
public record ApiError(String code, String message, Object details, String requestId) {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    public static ApiError of(HttpServletRequest request, String code, String message, Object details) {
        return new ApiError(code, message, details, requestId(request));
    }

    static String requestId(HttpServletRequest request) {
        String header = request.getHeader(REQUEST_ID_HEADER);
        return header != null && SAFE_ID.matcher(header).matches() ? header : UUID.randomUUID().toString();
    }

    /** For security filters, which run before Spring MVC message converters. Messages are fixed constants without quotes. */
    public static void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"details\":null,\"requestId\":\"" + requestId(request) + "\"}");
    }
}
