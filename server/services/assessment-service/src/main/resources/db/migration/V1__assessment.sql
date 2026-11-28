-- P16 khảo thí: question bank, exams assigned by the tutor of a confirmed period, one graded attempt per exam.

-- Multiple choice with 4 options. owner_tutor_id set = a tutor's private question, used only in that tutor's exams.
-- Questions written or imported by an admin are APPROVED at once; AI drafts (P17) wait in PENDING until reviewed.
-- Only APPROVED questions are picked into exams.
CREATE TABLE questions (
    id             uuid         PRIMARY KEY,
    subject_code   varchar(32)  NOT NULL,
    level_code     varchar(32)  NOT NULL,
    topic          varchar(100) NOT NULL,
    difficulty     smallint     NOT NULL CHECK (difficulty BETWEEN 1 AND 3),
    content        text         NOT NULL CHECK (length(content) BETWEEN 5 AND 4000),
    option_a       text         NOT NULL,
    option_b       text         NOT NULL,
    option_c       text         NOT NULL,
    option_d       text         NOT NULL,
    correct        char(1)      NOT NULL CHECK (correct IN ('A', 'B', 'C', 'D')),
    explanation    text         NOT NULL DEFAULT '',
    source         varchar(300) NOT NULL DEFAULT '',
    origin         varchar(8)   NOT NULL CHECK (origin IN ('MANUAL', 'IMPORT', 'AI', 'TUTOR')),
    status         varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    owner_tutor_id uuid,
    created_by     uuid         NOT NULL,
    reviewed_by    uuid,
    review_note    text,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    reviewed_at    timestamptz
);
CREATE INDEX questions_bank_idx ON questions (subject_code, level_code, status);

CREATE TABLE exams (
    id               uuid         PRIMARY KEY,
    plan_id          uuid         NOT NULL,
    tutor_id         uuid         NOT NULL,
    learner_id       uuid         NOT NULL,
    kind             varchar(16)  NOT NULL CHECK (kind IN ('PLACEMENT', 'PERIODIC', 'FINAL')),
    subject_code     varchar(32)  NOT NULL,
    level_code       varchar(32)  NOT NULL,
    title            varchar(200) NOT NULL,
    topics           text,
    question_count   smallint     NOT NULL CHECK (question_count BETWEEN 5 AND 50),
    duration_minutes smallint     NOT NULL CHECK (duration_minutes BETWEEN 5 AND 180),
    created_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX exams_plan_idx ON exams (plan_id);
CREATE INDEX exams_learner_idx ON exams (learner_id);
-- One placement test and one final test per period.
CREATE UNIQUE INDEX exams_one_placement_idx ON exams (plan_id) WHERE kind = 'PLACEMENT';
CREATE UNIQUE INDEX exams_one_final_idx ON exams (plan_id) WHERE kind = 'FINAL';

CREATE TABLE attempts (
    id            uuid        PRIMARY KEY,
    exam_id       uuid        NOT NULL UNIQUE REFERENCES exams (id),
    learner_id    uuid        NOT NULL,
    started_at    timestamptz NOT NULL DEFAULT now(),
    deadline_at   timestamptz NOT NULL,
    submitted_at  timestamptz,
    late          boolean     NOT NULL DEFAULT false,
    correct_count integer,
    total         integer     NOT NULL
);

-- option_order maps displayed options to the original ones: "CADB" shows original C as A, A as B, and so on.
CREATE TABLE attempt_items (
    attempt_id   uuid     NOT NULL REFERENCES attempts (id),
    position     smallint NOT NULL,
    question_id  uuid     NOT NULL REFERENCES questions (id),
    option_order char(4)  NOT NULL,
    answer       char(1),
    correct      boolean,
    PRIMARY KEY (attempt_id, position)
);
