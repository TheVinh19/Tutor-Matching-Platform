-- P12: at least one approved teaching video (YouTube link) before the profile is public; at most 3.
CREATE TABLE tutor_videos (
    id            uuid         PRIMARY KEY,
    account_id    uuid         NOT NULL REFERENCES tutor_profiles (account_id) ON DELETE CASCADE,
    url           varchar(300) NOT NULL,
    status        varchar(16)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reject_reason text,
    reviewed_by   uuid,
    reviewed_at   timestamptz,
    created_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX tutor_videos_account_idx ON tutor_videos (account_id);

-- P09: a subject added after approval is reviewed separately; only APPROVED rows count for search, profile and booking.
ALTER TABLE tutor_subjects ADD COLUMN status varchar(16) NOT NULL DEFAULT 'APPROVED' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'));
ALTER TABLE tutor_subjects ADD COLUMN reject_reason text;

-- Rank 2 entry evidence (two groups): degree/certificate and at least one year of teaching experience. Links, checked by the admin.
CREATE TABLE tutor_evidence (
    id         bigserial    PRIMARY KEY,
    account_id uuid         NOT NULL REFERENCES tutor_profiles (account_id) ON DELETE CASCADE,
    kind       varchar(16)  NOT NULL CHECK (kind IN ('DEGREE', 'EXPERIENCE')),
    url        varchar(500) NOT NULL,
    note       text,
    created_at timestamptz  NOT NULL DEFAULT now()
);
