-- P18 chăm sóc khách hàng: support tickets, FAQ (the source of the AI assistant), 4 care tiers, staff tasks and contact log.

CREATE TABLE tickets (
    id                uuid         PRIMARY KEY,
    author_id         uuid         NOT NULL,
    author_role       varchar(8)   NOT NULL CHECK (author_role IN ('LEARNER', 'TUTOR')),
    category          varchar(16)  NOT NULL CHECK (category IN ('SCHEDULE', 'TUTOR', 'PAYMENT', 'TECHNICAL', 'FEEDBACK', 'OTHER')),
    subject           varchar(200) NOT NULL,
    plan_id           uuid,
    session_id        uuid,
    status            varchar(16)  NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'IN_PROGRESS', 'RESOLVED')),
    source            varchar(8)   NOT NULL DEFAULT 'WEB' CHECK (source IN ('WEB', 'CHATBOT')),
    assignee_id       uuid,
    case_id           uuid,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    first_response_at timestamptz,
    resolved_at       timestamptz,
    updated_at        timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX tickets_author_idx ON tickets (author_id, created_at DESC);
CREATE INDEX tickets_status_idx ON tickets (status, created_at);

CREATE TABLE ticket_messages (
    id          bigserial   PRIMARY KEY,
    ticket_id   uuid        NOT NULL REFERENCES tickets (id),
    author_id   uuid        NOT NULL,
    author_role varchar(8)  NOT NULL,
    body        text        NOT NULL CHECK (length(body) BETWEEN 1 AND 4000),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE faqs (
    id               uuid         PRIMARY KEY,
    question         varchar(500) NOT NULL,
    answer           text         NOT NULL,
    tags             varchar(300) NOT NULL DEFAULT '',
    status           varchar(16)  NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED', 'HIDDEN')),
    source_ticket_id uuid         REFERENCES tickets (id),
    created_by       uuid         NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now()
);

-- Questions the AI assistant could not answer or answered unhelpfully, for staff to cover with FAQ (P17).
CREATE TABLE faq_gaps (
    id         bigserial   PRIMARY KEY,
    question   text        NOT NULL,
    reason     varchar(16) NOT NULL CHECK (reason IN ('NO_ANSWER', 'UNHELPFUL')),
    account_id uuid,
    resolved   boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE care_tiers (
    learner_id  uuid        PRIMARY KEY,
    tier        smallint    NOT NULL CHECK (tier BETWEEN 1 AND 4),
    reasons     text        NOT NULL,
    computed_at timestamptz NOT NULL
);
CREATE TABLE care_tier_history (
    id         bigserial   PRIMARY KEY,
    learner_id uuid        NOT NULL,
    tier       smallint    NOT NULL,
    reasons    text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX care_tier_history_learner_idx ON care_tier_history (learner_id, created_at DESC);

CREATE TABLE care_tasks (
    id         uuid        PRIMARY KEY,
    learner_id uuid        NOT NULL,
    tier       smallint    NOT NULL,
    reason     text        NOT NULL,
    status     varchar(8)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DONE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    done_at    timestamptz,
    done_by    uuid,
    done_note  text
);
-- One open task per learner: a new tier-3/4 day does not pile up duplicate calls.
CREATE UNIQUE INDEX care_tasks_one_open_idx ON care_tasks (learner_id) WHERE status = 'OPEN';

CREATE TABLE care_contacts (
    id         bigserial   PRIMARY KEY,
    learner_id uuid        NOT NULL,
    actor_id   uuid        NOT NULL,
    channel    varchar(16) NOT NULL CHECK (channel IN ('PHONE', 'MESSAGE', 'EMAIL', 'OTHER')),
    reached    boolean     NOT NULL,
    note       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Each time staff read a phone number (P18 audit trail).
CREATE TABLE contact_views (
    id         bigserial   PRIMARY KEY,
    actor_id   uuid        NOT NULL,
    account_id uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
