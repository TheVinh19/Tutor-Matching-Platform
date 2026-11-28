# Sơ đồ dự án

Sơ đồ Mermaid (xem được trên GitHub, VS Code). Bản ảnh PNG/SVG: [docs/diagrams/](../diagrams/). Nét liền: đã có trong code. Nét đứt: dự kiến theo [overview](overview.md) và [domain map](domain-map.md).

## 1. Triển khai (Docker Compose)

```mermaid
flowchart LR
    U[Người dùng<br/>Người thuê / Gia sư / Admin] --> N[Nginx :8088]
    N --> W[web<br/>Next.js :3000]
    N --> G[api-gateway<br/>Spring Cloud Gateway :8080]
    W --> G

    subgraph svc[Services - Spring Boot / Java 21]
        I[identity-service :8081]
        T[tutor-service :8082]
        B[booking-service :8083]
        P[payment-service :8084]
        F[notification-service :8085]
        AI[ai-service<br/>placeholder]
    end

    G -->|/api/v1/identity/**| I
    G -->|/api/v1/tutors/**| T
    G -->|/api/v1/bookings/**| B
    G -.->|/api/v1/payments/health| P
    G -->|/api/v1/notifications/**| F

    subgraph pg[PostgreSQL 17 - 1 instance, DB riêng mỗi service]
        D1[(account_db)]
        D2[(tutor_db)]
        D3[(learning_db)]
        D4[(payment_db)]
        D5[(notification_db)]
    end

    I --> D1
    T --> D2
    B --> D3
    P --> D4
    F --> D5

    K[[Kafka]]
    R[[Redis]]
    M[[Mailpit]]
    B -.-> K
    P -.-> K
    K -.-> T
    K -.-> F
    F -.-> M
```

## 2. Gọi nội bộ giữa service (REST + JWT nội bộ)

```mermaid
flowchart LR
    B[booking-service] -->|TutorClient<br/>GET /internal/profiles/id<br/>trạng thái, Hạng, môn/cấp| T[tutor-service]
    B -->|Notifier: tạo thông báo| F[notification-service]
    T -->|BookingClient<br/>GET /internal/availability/tutors<br/>GET /internal/plans/id/review-eligibility| B
    B -.->|giá, kết quả thanh toán| P[payment-service]
    T -.->|Hạng hiện hành| P
```

JWT do `identity-service` phát (`JwtIssuer`); gateway chuyển tiếp nguyên vẹn, mỗi service tự kiểm tra (`common-core`).

## 3. Dữ liệu theo service

```mermaid
flowchart TB
    subgraph account_db
        accounts
    end
    subgraph tutor_db
        tutor_profiles --- tutor_subjects
        tutor_profiles --- tutor_areas
        tutor_profiles --- approval_history
        tutor_profiles --- reviews
        subjects --- tutor_subjects
        levels --- tutor_subjects
        areas --- tutor_areas
    end
    subgraph learning_db
        availability_slots
        plans --- plan_preferred_slots
        plans --- sessions
        sessions --- session_offer_windows
    end
    subgraph notification_db
        notifications
    end
```

Liên kết xuyên DB chỉ qua ID (không khóa ngoại): `plans`/`sessions` tham chiếu tài khoản và gia sư bằng ID.

## 4. Luồng đặt học chính

```mermaid
sequenceDiagram
    actor L as Người thuê
    actor Tu as Gia sư
    participant W as web
    participant G as gateway
    participant B as booking
    participant T as tutor
    participant F as notification

    Tu->>W: Khai lịch rảnh
    W->>G: PUT /api/v1/bookings/availability/me
    G->>B: lưu availability_slots
    L->>W: Tìm gia sư, xem hồ sơ
    W->>G: /api/v1/tutors/**
    G->>T: tìm kiếm / hồ sơ
    T->>B: lọc theo lịch rảnh (internal)
    L->>W: Tạo kế hoạch học
    W->>G: POST /api/v1/bookings/plans
    G->>B: tạo plan
    B->>T: kiểm tra hồ sơ, môn/cấp
    B->>F: báo gia sư
    Tu->>B: xác nhận plan
    B->>B: sinh sessions, giữ lịch
    B->>F: báo người thuê
    Note over B: Đổi lịch: RescheduleController,<br/>session_offer_windows
```
