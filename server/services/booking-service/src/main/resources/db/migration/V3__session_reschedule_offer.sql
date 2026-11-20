-- A CANCEL_REPORTED session is moved either by the learner picking a free slot
-- of the tutor, or by the tutor picking one slot inside the weekly windows the learner can attend within [offer_from, offer_to].
-- Only the latest offer is kept. It holds nothing: the EXCLUDE constraints decide when the tutor picks.
ALTER TABLE sessions ADD COLUMN offer_from date;
ALTER TABLE sessions ADD COLUMN offer_to date;
ALTER TABLE sessions ADD CONSTRAINT sessions_offer_range CHECK (offer_to IS NULL OR offer_to >= offer_from);

CREATE TABLE session_offer_windows (
    session_id  uuid     NOT NULL REFERENCES sessions (id),
    day_of_week smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time  time     NOT NULL,
    end_time    time     NOT NULL,
    PRIMARY KEY (session_id, day_of_week, start_time),
    CHECK (start_time >= time '08:00' AND end_time <= time '22:00' AND end_time - start_time >= interval '120 minutes')
);
