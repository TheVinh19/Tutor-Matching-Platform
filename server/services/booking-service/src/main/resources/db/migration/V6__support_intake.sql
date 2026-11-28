-- P18 option B: customer care staff (role SUPPORT) receive cases, log every contact and propose an outcome;
-- only an admin concludes. Existing case/strike behaviour is unchanged.
ALTER TABLE cases ADD COLUMN proposed_outcome varchar(16) CHECK (proposed_outcome IN ('TUTOR_FAULT', 'LEARNER_WRONG', 'NO_FAULT'));
ALTER TABLE cases ADD COLUMN proposal_reason  text;
ALTER TABLE cases ADD COLUMN proposed_by      uuid;
ALTER TABLE cases ADD COLUMN proposed_at      timestamptz;

ALTER TABLE case_events DROP CONSTRAINT case_events_action_check;
ALTER TABLE case_events ADD CONSTRAINT case_events_action_check CHECK (action IN ('OPENED', 'DECIDED', 'APPEALED', 'PROPOSED'));

-- Every call/message to either party, including the P11 "at least 2 attempts within 24h" for a tutor who went silent.
CREATE TABLE case_contacts (
    id         bigserial   PRIMARY KEY,
    case_id    uuid        NOT NULL REFERENCES cases (id),
    actor_id   uuid        NOT NULL,
    party      varchar(8)  NOT NULL CHECK (party IN ('LEARNER', 'TUTOR')),
    channel    varchar(16) NOT NULL CHECK (channel IN ('PHONE', 'MESSAGE', 'EMAIL', 'OTHER')),
    reached    boolean     NOT NULL,
    note       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX case_contacts_case_idx ON case_contacts (case_id, created_at);

-- Learner strike proposed by staff with the contact history; counts only once an admin approves it (P14).
CREATE TABLE strike_proposals (
    id            bigserial   PRIMARY KEY,
    learner_id    uuid        NOT NULL,
    kind          varchar(16) NOT NULL CHECK (kind IN ('SELF_CANCEL', 'NO_SHOW')),
    session_id    uuid        REFERENCES sessions (id),
    reason        text        NOT NULL,
    proposed_by   uuid        NOT NULL,
    status        varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_by    uuid,
    decision_note text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    decided_at    timestamptz
);

-- Each time staff read a phone number to call a party (P18 audit trail).
CREATE TABLE contact_views (
    id         bigserial   PRIMARY KEY,
    actor_id   uuid        NOT NULL,
    account_id uuid        NOT NULL,
    case_id    uuid        REFERENCES cases (id),
    created_at timestamptz NOT NULL DEFAULT now()
);
