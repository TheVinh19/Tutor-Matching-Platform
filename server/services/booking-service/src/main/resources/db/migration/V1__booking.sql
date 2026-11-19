-- btree_gist lets EXCLUDE constraints mix uuid equality with range overlap. Trusted extension: the DB owner can create it.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- CA01-CA05: weekly teaching windows, 08:00-22:00, at least one 120-minute session long. Effective immediately, no admin approval.
CREATE TABLE availability_slots (
    id             uuid        PRIMARY KEY,
    tutor_id       uuid        NOT NULL,
    day_of_week    smallint    NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time     time        NOT NULL,
    end_time       time        NOT NULL,
    mode           varchar(16) NOT NULL CHECK (mode IN ('ONLINE', 'OFFLINE', 'FLEX')),
    effective_from date        NOT NULL,
    effective_to   date,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CHECK (start_time >= time '08:00' AND end_time <= time '22:00' AND end_time - start_time >= interval '120 minutes'),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX availability_tutor_idx ON availability_slots (tutor_id);
CREATE INDEX availability_day_idx ON availability_slots (day_of_week, start_time, end_time);

-- A plan = one study period: learner request -> tutor proposal (sessions held 24h) -> learner confirms.
CREATE TABLE plans (
    id              uuid        PRIMARY KEY,
    learner_id      uuid        NOT NULL,
    tutor_id        uuid        NOT NULL,
    subject_code    varchar(32) NOT NULL,
    level_code      varchar(32) NOT NULL,
    mode            varchar(16) NOT NULL CHECK (mode IN ('ONLINE', 'OFFLINE')),
    note            text        NOT NULL DEFAULT '',
    status          varchar(16) NOT NULL CHECK (status IN ('REQUESTED', 'PROPOSED', 'CONFIRMED', 'COMPLETED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    -- Mục 4 (escrow) replaces PAYMENT_PENDING_MOCK with the real payment state.
    payment_status  varchar(32),
    reject_reason   text,
    start_date      date,
    end_date        date,
    hold_expires_at timestamptz,
    meet_link       varchar(500),
    created_at      timestamptz NOT NULL DEFAULT now(),
    proposed_at     timestamptz,
    confirmed_at    timestamptz,
    closed_at       timestamptz,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (end_date IS NULL OR end_date >= start_date)
);
CREATE INDEX plans_learner_idx ON plans (learner_id, created_at DESC);
CREATE INDEX plans_tutor_idx ON plans (tutor_id, created_at DESC);
CREATE INDEX plans_hold_idx ON plans (hold_expires_at) WHERE status = 'PROPOSED';

-- block_until = end_at + 60 min (computed by the app: timestamptz + interval is not immutable, so it cannot sit in the index).
-- The two EXCLUDE constraints are the final guard against double booking, also under concurrent requests (P08).
CREATE TABLE sessions (
    id               uuid        PRIMARY KEY,
    plan_id          uuid        NOT NULL REFERENCES plans (id),
    tutor_id         uuid        NOT NULL,
    learner_id       uuid        NOT NULL,
    start_at         timestamptz NOT NULL,
    end_at           timestamptz NOT NULL,
    block_until      timestamptz NOT NULL,
    status           varchar(32) NOT NULL CHECK (status IN ('HELD', 'SCHEDULED', 'CANCEL_REPORTED', 'COMPLETED_PENDING_CONFIRM',
                                                            'CONFIRMED', 'DISPUTED', 'CANCELLED', 'RELEASED')),
    meet_link        varchar(500),
    cancel_reason    text,
    cancelled_by     uuid,
    cancelled_at     timestamptz,
    completed_at     timestamptz,
    confirm_deadline timestamptz,
    confirmed_at     timestamptz,
    auto_confirmed   boolean     NOT NULL DEFAULT false,
    dispute_reason   text,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CHECK (end_at > start_at AND block_until >= end_at),
    CONSTRAINT sessions_tutor_no_overlap EXCLUDE USING gist (tutor_id WITH =, tstzrange(start_at, block_until) WITH &&)
        WHERE (status NOT IN ('CANCELLED', 'RELEASED')),
    CONSTRAINT sessions_learner_no_overlap EXCLUDE USING gist (learner_id WITH =, tstzrange(start_at, end_at) WITH &&)
        WHERE (status NOT IN ('CANCELLED', 'RELEASED'))
);
CREATE INDEX sessions_plan_idx ON sessions (plan_id, start_at);
CREATE INDEX sessions_tutor_idx ON sessions (tutor_id, start_at);
CREATE INDEX sessions_learner_idx ON sessions (learner_id, start_at);
CREATE INDEX sessions_confirm_idx ON sessions (confirm_deadline) WHERE status = 'COMPLETED_PENDING_CONFIRM';
