# ERD cơ sở dữ liệu

Sinh từ migration Flyway (`server/services/*/src/main/resources/db/migration`). Mỗi service một database riêng trên cùng instance PostgreSQL.

- Nét liền: khóa ngoại thật trong cùng database.
- Nét đứt: liên kết logic bằng ID, không có khóa ngoại (khác database hoặc cố ý không ràng buộc).

Bản ảnh: [erd.png](../diagrams/erd.png), [erd.svg](../diagrams/erd.svg).

```mermaid
erDiagram
    %% ===== account_db (identity-service) =====
    accounts {
        uuid id PK
        varchar(254) email UK "nullable, lowercase"
        varchar(50) username UK "nullable, lowercase"
        varchar(100) password_hash
        varchar(16) role "LEARNER | TUTOR | ADMIN"
        varchar(16) status "ACTIVE | RESTRICTED"
        timestamptz created_at
    }

    %% ===== tutor_db (tutor-service) =====
    subjects {
        varchar(32) code PK
        varchar(100) name
        boolean primary_school
    }
    levels {
        varchar(32) code PK
        varchar(100) name
        smallint sort_order
    }
    areas {
        varchar(32) code PK
        varchar(100) province
        varchar(100) name
    }
    tutor_profiles {
        uuid account_id PK "= accounts.id"
        varchar(100) full_name
        text bio
        text experience
        varchar(16) teaching_mode "ONLINE | OFFLINE | FLEX"
        smallint rank
        varchar(16) status "DRAFT | PENDING | APPROVED | REJECTED"
        text reject_reason
        timestamptz submitted_at
        timestamptz reviewed_at
        uuid reviewed_by
        timestamptz updated_at
    }
    tutor_subjects {
        uuid account_id PK, FK
        varchar(32) subject_code PK, FK
        varchar(32) level_code PK, FK
    }
    tutor_areas {
        uuid account_id PK, FK
        varchar(32) area_code PK, FK
    }
    approval_history {
        bigserial id PK
        uuid account_id FK
        varchar(16) action "SUBMIT | APPROVE | REJECT"
        text reason
        uuid actor_id
        timestamptz created_at
    }
    reviews {
        uuid id PK
        uuid plan_id UK
        uuid tutor_id
        uuid learner_id
        text content "10-2000 ký tự"
        boolean hidden
        timestamptz created_at
    }

    %% ===== learning_db (booking-service) =====
    availability_slots {
        uuid id PK
        uuid tutor_id
        smallint day_of_week "1-7"
        time start_time
        time end_time
        varchar(16) mode "ONLINE | OFFLINE | FLEX"
        date effective_from
        date effective_to
        timestamptz created_at
    }
    plans {
        uuid id PK
        uuid learner_id
        uuid tutor_id
        varchar(32) subject_code
        varchar(32) level_code
        varchar(16) mode "ONLINE | OFFLINE"
        text note
        varchar(16) status "REQUESTED | PROPOSED | CONFIRMED | ..."
        varchar(32) payment_status
        text reject_reason
        date start_date
        date end_date
        smallint sessions_per_week "1-7"
        smallint weeks "1-26"
        timestamptz hold_expires_at
        varchar(500) meet_link
        timestamptz created_at
        timestamptz proposed_at
        timestamptz confirmed_at
        timestamptz closed_at
        timestamptz updated_at
    }
    plan_preferred_slots {
        uuid plan_id PK, FK
        smallint day_of_week PK
        time start_time PK
    }
    sessions {
        uuid id PK
        uuid plan_id FK
        uuid tutor_id
        uuid learner_id
        timestamptz start_at
        timestamptz end_at
        timestamptz block_until "end_at + 60 phút"
        varchar(32) status "HELD | SCHEDULED | CANCEL_REPORTED | ..."
        varchar(500) meet_link
        text cancel_reason
        uuid cancelled_by
        timestamptz cancelled_at
        timestamptz completed_at
        timestamptz confirm_deadline
        timestamptz confirmed_at
        boolean auto_confirmed
        text dispute_reason
        date offer_from
        date offer_to
        timestamptz updated_at
    }
    session_offer_windows {
        uuid session_id PK, FK
        smallint day_of_week PK
        time start_time PK
        time end_time
    }

    %% ===== notification_db (notification-service) =====
    notifications {
        uuid id PK
        uuid recipient_id
        varchar(64) type
        varchar(200) title
        text body
        varchar(32) ref_type
        uuid ref_id
        timestamptz created_at
        timestamptz read_at
    }

    %% Khóa ngoại thật
    tutor_profiles ||--o{ tutor_subjects : "dạy"
    subjects ||--o{ tutor_subjects : ""
    levels ||--o{ tutor_subjects : ""
    tutor_profiles ||--o{ tutor_areas : "khu vực"
    areas ||--o{ tutor_areas : ""
    tutor_profiles ||--o{ approval_history : "lịch sử duyệt"
    plans ||--o{ plan_preferred_slots : "ô lịch mong muốn"
    plans ||--o{ sessions : "sinh buổi"
    sessions ||--o{ session_offer_windows : "khung đổi lịch"

    %% Liên kết logic (không FK)
    accounts ||..o| tutor_profiles : "account_id"
    accounts ||..o{ plans : "learner_id"
    tutor_profiles ||..o{ plans : "tutor_id"
    tutor_profiles ||..o{ availability_slots : "tutor_id"
    plans ||..o| reviews : "plan_id"
    tutor_profiles ||..o{ reviews : "tutor_id"
    accounts ||..o{ notifications : "recipient_id"
```
