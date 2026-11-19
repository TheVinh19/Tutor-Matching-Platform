-- Accounts: one email = one role (P01). Demo accounts are upserted at startup by DemoAccountSeeder, not here,
-- so the demo password never lands in a migration.
CREATE TABLE accounts (
    id            uuid         PRIMARY KEY,
    email         varchar(254) NOT NULL UNIQUE CHECK (email = lower(email)),
    password_hash varchar(100) NOT NULL,
    role          varchar(16)  NOT NULL CHECK (role IN ('LEARNER', 'TUTOR', 'ADMIN')),
    status        varchar(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RESTRICTED')),
    created_at    timestamptz  NOT NULL DEFAULT now()
);
