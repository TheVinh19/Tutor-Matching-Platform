-- P09/P13: EXP ledger. One row per event; source_key is unique per tutor so syncing from booking twice never double counts.
-- EXP shown = max(0, sum(points)). Rows are never deleted; corrections are new rows.
CREATE TABLE exp_ledger (
    id         uuid        PRIMARY KEY,
    tutor_id   uuid        NOT NULL REFERENCES tutor_profiles (account_id),
    source_key varchar(100) NOT NULL,
    type       varchar(24) NOT NULL CHECK (type IN ('INITIAL', 'SESSION_ON_TIME', 'PERIOD_COMPLETED', 'RENEWAL', 'PENALTY')),
    points     integer     NOT NULL,
    ref_type   varchar(16),
    ref_id     uuid,
    reason     text,
    actor_id   uuid,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tutor_id, source_key)
);
CREATE INDEX exp_ledger_tutor_idx ON exp_ledger (tutor_id, created_at DESC);

-- P10 inputs copied from booking at each sync, for the admin list and the public profile without calling booking per view.
CREATE TABLE tutor_stats (
    account_id         uuid        PRIMARY KEY REFERENCES tutor_profiles (account_id),
    taught_sessions    integer     NOT NULL DEFAULT 0,
    tutor_fault_failed integer     NOT NULL DEFAULT 0,
    distinct_learners  integer     NOT NULL DEFAULT 0,
    renewals           integer     NOT NULL DEFAULT 0,
    open_issues        integer     NOT NULL DEFAULT 0,
    synced_at          timestamptz NOT NULL DEFAULT now()
);

-- Rank is changed only by an admin decision (never by a job), with reason and effective time (P09/P10).
ALTER TABLE tutor_profiles ADD COLUMN rank_since timestamptz;
ALTER TABLE tutor_profiles ADD COLUMN entry_rank2 boolean NOT NULL DEFAULT false;
UPDATE tutor_profiles SET rank_since = coalesce(reviewed_at, now()) WHERE status = 'APPROVED';
CREATE TABLE rank_decisions (
    id         bigserial   PRIMARY KEY,
    account_id uuid        NOT NULL REFERENCES tutor_profiles (account_id),
    from_rank  smallint    NOT NULL,
    to_rank    smallint    NOT NULL CHECK (to_rank BETWEEN 1 AND 3),
    reason     text        NOT NULL,
    actor_id   uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- P12: the tutor replies once or reports a violation; admins only hide/restore the whole review, keeping the original.
ALTER TABLE reviews ADD COLUMN reply         text CHECK (length(reply) BETWEEN 1 AND 2000);
ALTER TABLE reviews ADD COLUMN replied_at    timestamptz;
ALTER TABLE reviews ADD COLUMN report_reason text;
ALTER TABLE reviews ADD COLUMN reported_at   timestamptz;
CREATE TABLE review_moderation (
    id         bigserial   PRIMARY KEY,
    review_id  uuid        NOT NULL REFERENCES reviews (id),
    action     varchar(8)  NOT NULL CHECK (action IN ('HIDE', 'RESTORE')),
    reason     text        NOT NULL,
    actor_id   uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
