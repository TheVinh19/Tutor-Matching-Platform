-- Sign-up with username + password only; email/phone get linked later. Demo accounts keep logging in by email.
ALTER TABLE accounts ALTER COLUMN email DROP NOT NULL;
ALTER TABLE accounts ADD COLUMN username varchar(50) UNIQUE CHECK (username = lower(username));
ALTER TABLE accounts ADD CONSTRAINT accounts_login_required CHECK (email IS NOT NULL OR username IS NOT NULL);
