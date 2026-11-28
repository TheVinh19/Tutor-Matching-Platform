-- P18: customer care staff (nhân viên CSKH). Accounts are created by an admin only, never by public sign-up.
ALTER TABLE accounts DROP CONSTRAINT accounts_role_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_role_check CHECK (role IN ('LEARNER', 'TUTOR', 'ADMIN', 'SUPPORT'));
