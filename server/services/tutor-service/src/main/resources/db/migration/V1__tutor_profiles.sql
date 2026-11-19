-- Catalog (codes used by profiles and search; GS01, MAP01).
CREATE TABLE subjects (
    code varchar(32)  PRIMARY KEY,
    name varchar(100) NOT NULL
);
CREATE TABLE levels (
    code       varchar(32)  PRIMARY KEY,
    name       varchar(100) NOT NULL,
    sort_order smallint     NOT NULL
);
CREATE TABLE areas (
    code     varchar(32)  PRIMARY KEY,
    province varchar(100) NOT NULL,
    name     varchar(100) NOT NULL
);

INSERT INTO subjects (code, name) VALUES
    ('TOAN', 'Toán'), ('VAN', 'Ngữ văn'), ('ANH', 'Tiếng Anh'),
    ('LY', 'Vật lý'), ('HOA', 'Hóa học'), ('SINH', 'Sinh học');
INSERT INTO levels (code, name, sort_order) VALUES
    ('TIEU_HOC', 'Tiểu học', 1), ('THCS', 'THCS', 2), ('THPT', 'THPT', 3), ('LUYEN_THI', 'Luyện thi đại học', 4);
INSERT INTO areas (code, province, name) VALUES
    ('HN-CG', 'Hà Nội', 'Cầu Giấy'), ('HN-DD', 'Hà Nội', 'Đống Đa'), ('HN-HBT', 'Hà Nội', 'Hai Bà Trưng'),
    ('HCM-Q1', 'TP. Hồ Chí Minh', 'Quận 1'), ('HCM-Q3', 'TP. Hồ Chí Minh', 'Quận 3'), ('HCM-BT', 'TP. Hồ Chí Minh', 'Bình Thạnh');

-- One profile per tutor account (account_id comes from identity-service, no cross-DB FK).
CREATE TABLE tutor_profiles (
    account_id    uuid         PRIMARY KEY,
    full_name     varchar(100) NOT NULL,
    bio           text         NOT NULL DEFAULT '',
    experience    text         NOT NULL DEFAULT '',
    teaching_mode varchar(16)  NOT NULL CHECK (teaching_mode IN ('ONLINE', 'OFFLINE', 'FLEX')),
    rank          smallint     NOT NULL DEFAULT 1 CHECK (rank >= 1),
    status        varchar(16)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED')),
    reject_reason text,
    submitted_at  timestamptz,
    reviewed_at   timestamptz,
    reviewed_by   uuid,
    updated_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX tutor_profiles_status_idx ON tutor_profiles (status);

CREATE TABLE tutor_subjects (
    account_id   uuid        NOT NULL REFERENCES tutor_profiles (account_id) ON DELETE CASCADE,
    subject_code varchar(32) NOT NULL REFERENCES subjects (code),
    level_code   varchar(32) NOT NULL REFERENCES levels (code),
    PRIMARY KEY (account_id, subject_code, level_code)
);
CREATE TABLE tutor_areas (
    account_id uuid        NOT NULL REFERENCES tutor_profiles (account_id) ON DELETE CASCADE,
    area_code  varchar(32) NOT NULL REFERENCES areas (code),
    PRIMARY KEY (account_id, area_code)
);

-- Every submit/approve/reject is kept with actor, reason and time.
CREATE TABLE approval_history (
    id         bigserial   PRIMARY KEY,
    account_id uuid        NOT NULL REFERENCES tutor_profiles (account_id) ON DELETE CASCADE,
    action     varchar(16) NOT NULL CHECK (action IN ('SUBMIT', 'APPROVE', 'REJECT')),
    reason     text,
    actor_id   uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Text-only review per completed plan (DG02): no stars, no edits, one per plan.
CREATE TABLE reviews (
    id         uuid        PRIMARY KEY,
    plan_id    uuid        NOT NULL UNIQUE,
    tutor_id   uuid        NOT NULL,
    learner_id uuid        NOT NULL,
    content    text        NOT NULL CHECK (length(content) BETWEEN 10 AND 2000),
    hidden     boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reviews_tutor_idx ON reviews (tutor_id, created_at DESC);
