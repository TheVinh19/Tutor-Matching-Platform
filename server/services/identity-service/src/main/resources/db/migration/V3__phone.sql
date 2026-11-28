-- Contact phone (KN01 revised): shown to the other party only once a plan is confirmed. Not verified yet.
ALTER TABLE accounts ADD COLUMN phone varchar(10) CHECK (phone ~ '^0[0-9]{9}$');
