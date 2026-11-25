-- Session lifecycle (P04/P11): tutor presses "start" (server time, late > 10 min recorded), early end needs who + why,
-- absence branches, cancel reports expire. Periods (plans) close as HOAN_TAT_NGHIA_VU / KET_THUC_CO_HOAN_MOT_PHAN,
-- or stay HET_KY_CON_NGHIA_VU while a dispute/absence report is open (P07). Renewals link to the previous period (P09).

ALTER TABLE sessions DROP CONSTRAINT sessions_status_check;
ALTER TABLE sessions ADD CONSTRAINT sessions_status_check CHECK (status IN (
    'HELD', 'SCHEDULED', 'IN_PROGRESS', 'CANCEL_REPORTED', 'COMPLETED_PENDING_CONFIRM', 'CONFIRMED', 'DISPUTED',
    'LEARNER_ABSENT_PENDING', 'LEARNER_ABSENT', 'TUTOR_ABSENT_REPORTED', 'CANCELLED', 'RELEASED'));

ALTER TABLE sessions ADD COLUMN started_at      timestamptz;
ALTER TABLE sessions ADD COLUMN late_minutes    integer CHECK (late_minutes > 0);
ALTER TABLE sessions ADD COLUMN early_end_by    varchar(8) CHECK (early_end_by IN ('LEARNER', 'TUTOR'));
ALTER TABLE sessions ADD COLUMN early_end_note  text;
ALTER TABLE sessions ADD COLUMN absent_note     text;
ALTER TABLE sessions ADD COLUMN absent_deadline timestamptz;
ALTER TABLE sessions ADD COLUMN cancel_deadline timestamptz;
ALTER TABLE sessions ADD COLUMN reminded_60_at  timestamptz;
ALTER TABLE sessions ADD COLUMN reminded_30_at  timestamptz;

ALTER TABLE plans DROP CONSTRAINT plans_status_check;
ALTER TABLE plans ALTER COLUMN status TYPE varchar(32);
UPDATE plans SET status = 'HOAN_TAT_NGHIA_VU' WHERE status = 'COMPLETED';
ALTER TABLE plans ADD CONSTRAINT plans_status_check CHECK (status IN (
    'REQUESTED', 'PROPOSED', 'CONFIRMED', 'HOAN_TAT_NGHIA_VU', 'KET_THUC_CO_HOAN_MOT_PHAN', 'HET_KY_CON_NGHIA_VU',
    'REJECTED', 'CANCELLED', 'EXPIRED'));

ALTER TABLE plans ADD COLUMN renewed_from         uuid REFERENCES plans (id);
ALTER TABLE plans ADD COLUMN renewal_reminded_at  timestamptz;
-- Input for +50 EXP later: set once when the new period is paid and the old one HOAN_TAT_NGHIA_VU, whichever comes last.
ALTER TABLE plans ADD COLUMN renewal_qualified_at timestamptz;
-- At most one open renewal per period; also the guard against double clicks.
CREATE UNIQUE INDEX plans_open_renewal_idx ON plans (renewed_from) WHERE status IN ('REQUESTED', 'PROPOSED');

-- Moving one weekly slot of a confirmed period from a date on (DOI05). The other party must accept; unanswered requests expire
-- and the old schedule stays (P12). The EXCLUDE constraints on sessions decide conflicts at accept time.
CREATE TABLE slot_changes (
    id             uuid        PRIMARY KEY,
    plan_id        uuid        NOT NULL REFERENCES plans (id),
    requested_by   uuid        NOT NULL,
    from_day       smallint    NOT NULL CHECK (from_day BETWEEN 1 AND 7),
    from_time      time        NOT NULL,
    to_day         smallint    NOT NULL CHECK (to_day BETWEEN 1 AND 7),
    to_time        time        NOT NULL,
    effective_from date        NOT NULL,
    status         varchar(16) NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELLED')),
    decline_reason text,
    expires_at     timestamptz NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    decided_at     timestamptz
);
CREATE UNIQUE INDEX slot_changes_one_pending_idx ON slot_changes (plan_id) WHERE status = 'PENDING';
