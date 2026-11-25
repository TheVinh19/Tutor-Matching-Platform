# Luồng hoạt động

Theo code hiện tại của `booking-service`, `tutor-service`, `identity-service`. Mỗi bước gửi thông báo đi qua `notification-service` (`POST /api/v1/notifications/internal`).

Bản ảnh PNG/SVG: thư mục [docs/diagrams/](../diagrams/).

## 1. Đăng ký, đăng nhập, duyệt hồ sơ gia sư

```mermaid
flowchart TD
    A([Bắt đầu]) --> B[Đăng ký<br/>POST /auth/register]
    B --> C[Đăng nhập<br/>POST /auth/login]
    C --> D{Vai trò?}
    D -->|LEARNER| L([Tìm gia sư])
    D -->|ADMIN| AD([Trang quản trị])
    D -->|TUTOR| E[Khai hồ sơ: môn/cấp, khu vực, hình thức<br/>PUT /api/v1/tutors/me/profile]
    E --> F[Gửi duyệt<br/>POST /me/profile/submit<br/>DRAFT sang PENDING]
    F --> G{Admin duyệt}
    G -->|approve| H[APPROVED<br/>hiện trong tìm kiếm]
    G -->|reject + lý do| I[REJECTED]
    I --> E
    H --> J[Khai lịch nhận dạy<br/>PUT /api/v1/bookings/availability/me<br/>08:00-22:00, mỗi khung ≥ 120 phút]
    J --> K([Sẵn sàng nhận học sinh])
```

## 2. Đặt học: yêu cầu, lập kế hoạch, xác nhận

```mermaid
flowchart TD
    S([Học sinh]) --> A[Tìm gia sư<br/>GET /api/v1/tutors/search<br/>môn, cấp, Hạng, hình thức, khu vực, lịch]
    A --> B[Xem hồ sơ, nhận xét]
    B --> C[Gửi yêu cầu học<br/>POST /api/v1/bookings/plans<br/>số buổi/tuần, số tuần, ngày bắt đầu, ô lịch mong muốn]
    C --> V1{Gia sư dạy môn/cấp<br/>và hình thức này?}
    V1 -->|không| X1[Lỗi SUBJECT_NOT_OFFERED<br/>hoặc MODE_NOT_OFFERED]
    V1 -->|có| R[Plan REQUESTED<br/>báo gia sư]
    R --> T{Gia sư xử lý}
    R -->|học sinh hủy| CN[CANCELLED]
    T -->|từ chối + lý do| RJ[REJECTED<br/>báo học sinh]
    T -->|lập kế hoạch<br/>POST /plans/id/propose| P1{Ca nằm trong ô học sinh chọn,<br/>trong lịch nhận dạy,<br/>không trùng lịch, cách ≥ 60 phút?}
    P1 -->|không| X2[Lỗi NOT_PREFERRED_SLOT /<br/>OUTSIDE_AVAILABILITY /<br/>SCHEDULE_CONFLICT]
    X2 --> T
    P1 -->|có| P2[Sinh sessions HELD<br/>Plan PROPOSED, giữ lịch 24h<br/>báo học sinh]
    P2 --> Q{Học sinh}
    Q -->|xác nhận trong 24h<br/>POST /plans/id/confirm| OK[Plan CONFIRMED<br/>sessions SCHEDULED<br/>payment_status = PAYMENT_PENDING_MOCK]
    Q -->|hủy| CN2[CANCELLED<br/>sessions RELEASED]
    Q -->|quá 24h, HoldJobs| EX[EXPIRED<br/>sessions RELEASED]
    OK --> Z([Vào lịch học])
```

## 3. Buổi học: dạy, nghiệm thu, phản đối

```mermaid
flowchart TD
    A([Buổi SCHEDULED]) --> B{Trước giờ học}
    B -->|gia sư đổi link Meet<br/>PUT /sessions/id/meet-link| A
    B -->|một bên báo bận/hủy<br/>POST /sessions/id/cancel| CR([CANCEL_REPORTED<br/>sang luồng đổi lịch])
    B -->|đến giờ học| C[Gia sư báo đã dạy<br/>POST /sessions/id/complete]
    C --> D[COMPLETED_PENDING_CONFIRM<br/>hạn xác nhận 24h<br/>báo học sinh]
    D --> E{Học sinh trong 24h}
    E -->|xác nhận| F[CONFIRMED]
    E -->|phản đối + lý do| G[DISPUTED<br/>báo gia sư]
    E -->|im lặng quá 24h<br/>HoldJobs| H[CONFIRMED<br/>auto_confirmed = true]
    F --> I{Hết buổi của plan?}
    H --> I
    I -->|có| J[Plan COMPLETED<br/>học sinh được viết nhận xét<br/>POST /api/v1/tutors/reviews]
    I -->|chưa| A
```

## 4. Đổi lịch buổi đã báo bận/hủy

```mermaid
flowchart TD
    A([Buổi CANCEL_REPORTED]) --> B{Ai xếp lại?}
    B -->|Học sinh tự chọn| C[Xem ca trống của gia sư<br/>GET /sessions/id/reschedule/free]
    C --> D[Chọn 1 ca<br/>POST /sessions/id/reschedule]
    B -->|Học sinh gửi khung có thể học| E[Gửi offer: khoảng ngày + khung giờ theo tuần<br/>POST /sessions/id/reschedule/offer<br/>báo gia sư]
    E --> F{Gia sư}
    F -->|chọn 1 ca trong khung| D
    F -->|không xếp được + lý do<br/>POST /reschedule/decline| G[Báo học sinh] --> B
    D --> H{Ca còn trống?<br/>EXCLUDE constraint}
    H -->|không| X[Lỗi SLOT_NOT_AVAILABLE /<br/>SCHEDULE_CONFLICT] --> B
    H -->|có| OK[Buổi SCHEDULED giờ mới<br/>xóa offer<br/>báo bên còn lại]
```

## 5. Trạng thái kế hoạch (plans.status)

```mermaid
stateDiagram-v2
    [*] --> REQUESTED: học sinh gửi yêu cầu
    REQUESTED --> PROPOSED: gia sư lập kế hoạch, giữ lịch 24h
    REQUESTED --> REJECTED: gia sư từ chối
    REQUESTED --> CANCELLED: học sinh hủy
    PROPOSED --> CONFIRMED: học sinh xác nhận
    PROPOSED --> CANCELLED: học sinh hủy
    PROPOSED --> EXPIRED: quá hạn giữ lịch
    CONFIRMED --> COMPLETED: mọi buổi đã nghiệm thu
    REJECTED --> [*]
    CANCELLED --> [*]
    EXPIRED --> [*]
    COMPLETED --> [*]
```

## 6. Trạng thái buổi học (sessions.status)

```mermaid
stateDiagram-v2
    [*] --> HELD: plan PROPOSED
    HELD --> SCHEDULED: plan CONFIRMED
    HELD --> RELEASED: plan hủy / hết hạn
    SCHEDULED --> CANCEL_REPORTED: báo bận/hủy
    CANCEL_REPORTED --> SCHEDULED: đổi lịch thành công
    SCHEDULED --> COMPLETED_PENDING_CONFIRM: gia sư báo đã dạy
    COMPLETED_PENDING_CONFIRM --> CONFIRMED: học sinh xác nhận / tự động sau 24h
    COMPLETED_PENDING_CONFIRM --> DISPUTED: học sinh phản đối
    RELEASED --> [*]
    CONFIRMED --> [*]
    DISPUTED --> [*]
```
