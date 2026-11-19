-- Web inbox. Business services request notices; this service only stores and serves them.
CREATE TABLE notifications (
    id           uuid         PRIMARY KEY,
    recipient_id uuid         NOT NULL,
    type         varchar(64)  NOT NULL,
    title        varchar(200) NOT NULL,
    body         text         NOT NULL DEFAULT '',
    ref_type     varchar(32),
    ref_id       uuid,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    read_at      timestamptz
);
CREATE INDEX notifications_recipient_idx ON notifications (recipient_id, created_at DESC);
