-- Add Lịch sử, Địa lý. Tiểu học (TIEU_HOC) may only be taught for subjects flagged primary_school.
ALTER TABLE subjects ADD COLUMN primary_school boolean NOT NULL DEFAULT false;
UPDATE subjects SET primary_school = true WHERE code IN ('TOAN', 'VAN', 'ANH');
INSERT INTO subjects (code, name) VALUES ('SU', 'Lịch sử'), ('DIA', 'Địa lý');

-- Drop any existing combinations that the new rule forbids.
DELETE FROM tutor_subjects ts USING subjects s
WHERE ts.subject_code = s.code AND ts.level_code = 'TIEU_HOC' AND NOT s.primary_school;
