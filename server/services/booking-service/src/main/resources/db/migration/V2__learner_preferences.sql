-- Learner picks how many sessions per week, for how long, and which weekly grid cells suit them;
-- the tutor then chooses exactly sessions_per_week of those cells. Null on plans made before this (free-text note flow).
ALTER TABLE plans ADD COLUMN sessions_per_week smallint CHECK (sessions_per_week BETWEEN 1 AND 7);
ALTER TABLE plans ADD COLUMN weeks smallint CHECK (weeks BETWEEN 1 AND 26);

-- Not a hold: other learners may still take these cells until the tutor proposes.
CREATE TABLE plan_preferred_slots (
    plan_id     uuid     NOT NULL REFERENCES plans (id),
    day_of_week smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time  time     NOT NULL,
    PRIMARY KEY (plan_id, day_of_week, start_time)
);
