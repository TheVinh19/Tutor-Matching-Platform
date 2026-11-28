-- P07/P12/P14 cases: a disputed or tutor-absent session opens one case; the tutor has 72h to submit evidence; the admin decides
-- (tutor fault / learner wrong / no fault); either party may appeal any number of times. Evidence is links, never deleted.
ALTER TABLE sessions DROP CONSTRAINT sessions_status_check;
ALTER TABLE sessions ADD CONSTRAINT sessions_status_check CHECK (status IN (
    'HELD', 'SCHEDULED', 'IN_PROGRESS', 'CANCEL_REPORTED', 'COMPLETED_PENDING_CONFIRM', 'CONFIRMED', 'DISPUTED',
    'LEARNER_ABSENT_PENDING', 'LEARNER_ABSENT', 'TUTOR_ABSENT_REPORTED', 'FAILED_TUTOR', 'CANCELLED', 'RELEASED'));

CREATE TABLE cases (
    id                uuid        PRIMARY KEY,
    session_id        uuid        NOT NULL UNIQUE REFERENCES sessions (id),
    plan_id           uuid        NOT NULL REFERENCES plans (id),
    learner_id        uuid        NOT NULL,
    tutor_id          uuid        NOT NULL,
    kind              varchar(16) NOT NULL CHECK (kind IN ('DISPUTE', 'TUTOR_ABSENT')),
    status            varchar(16) NOT NULL CHECK (status IN ('OPEN', 'DECIDED', 'APPEALED')),
    reason            text        NOT NULL,
    evidence_deadline timestamptz NOT NULL,
    outcome           varchar(16) CHECK (outcome IN ('TUTOR_FAULT', 'LEARNER_WRONG', 'NO_FAULT')),
    decision_reason   text,
    decided_by        uuid,
    decided_at        timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX cases_status_idx ON cases (status, created_at DESC);

CREATE TABLE case_evidence (
    id           bigserial   PRIMARY KEY,
    case_id      uuid        NOT NULL REFERENCES cases (id),
    submitted_by uuid        NOT NULL,
    party        varchar(8)  NOT NULL CHECK (party IN ('LEARNER', 'TUTOR')),
    url          varchar(500) NOT NULL,
    note         text,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- Opened / decided / appealed, with who and why: the case history shown to both parties and the admin.
CREATE TABLE case_events (
    id         bigserial   PRIMARY KEY,
    case_id    uuid        NOT NULL REFERENCES cases (id),
    action     varchar(16) NOT NULL CHECK (action IN ('OPENED', 'DECIDED', 'APPEALED')),
    actor_id   uuid,
    note       text,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- P14 learner sanctions: warnings 1-2, the 3rd verified strike within 90 days blocks new plans for 30 days.
CREATE TABLE learner_strikes (
    id               bigserial   PRIMARY KEY,
    learner_id       uuid        NOT NULL,
    kind             varchar(16) NOT NULL CHECK (kind IN ('SELF_CANCEL', 'NO_SHOW', 'FALSE_COMPLAINT')),
    case_id          uuid        REFERENCES cases (id),
    session_id       uuid        REFERENCES sessions (id),
    reason           text        NOT NULL,
    actor_id         uuid        NOT NULL,
    restricted_until timestamptz,
    revoked_at       timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX learner_strikes_learner_idx ON learner_strikes (learner_id, created_at DESC);
