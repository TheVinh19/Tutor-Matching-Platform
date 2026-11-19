# common-core

Thư viện jar dùng chung cho các service servlet (không dùng cho api-gateway WebFlux). Tự cấu hình qua Spring Boot auto-configuration:

- `JwtSecurityAutoConfiguration`: kiểm tra JWT HS256 bằng `JWT_SECRET` (≥32 byte, thiếu thì service không khởi động), map claim `role` sang `ROLE_*`, bật `@PreAuthorize`.
- `ApiSecurity.stateless(http)`: cấu hình mặc định cho API stateless; mỗi service tự thêm luật `authorizeHttpRequests`.
- `JwtIssuer`: identity dùng để cấp token; test dùng để tạo token.
- `InternalClients`: `RestClient` gọi `/api/v1/<khu>/internal/**` của service khác bằng token role `SERVICE` hạn 5 phút. Gateway chặn các đường dẫn internal.
- `ApiError` / `ApiException` / `ApiExceptionHandler`: lỗi chuẩn `{code,message,details,requestId}`, không lộ stack trace.
