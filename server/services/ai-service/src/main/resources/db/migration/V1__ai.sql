-- P17 trợ lý AI: each question and answer is kept for accuracy review, the daily limit and escalation to customer care.

CREATE TABLE chat_turns (
    id              uuid         PRIMARY KEY,
    conversation_id uuid         NOT NULL,
    account_id      uuid         NOT NULL,
    account_role    varchar(8)   NOT NULL,
    question        text         NOT NULL CHECK (length(question) BETWEEN 1 AND 1000),
    answer          text         NOT NULL,
    matched         boolean      NOT NULL,
    source_type     varchar(8)   CHECK (source_type IN ('FAQ', 'POLICY')),
    source_ref      varchar(64),
    score           float8       NOT NULL,
    helpful         boolean,
    ticket_id       uuid,
    created_at      timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX chat_turns_account_idx ON chat_turns (account_id, created_at DESC);
CREATE INDEX chat_turns_conversation_idx ON chat_turns (conversation_id, created_at);
