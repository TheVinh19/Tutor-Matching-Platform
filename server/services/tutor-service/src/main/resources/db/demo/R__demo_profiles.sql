-- Demo data for the 5 mock accounts (fixed UUIDs from identity DemoAccountSeeder).
-- tutor1 = approved, tutor2 = pending review. Idempotent: safe to re-run.
INSERT INTO tutor_profiles (account_id, full_name, bio, experience, teaching_mode, rank, status, submitted_at, reviewed_at, reviewed_by)
VALUES ('00000000-0000-0000-0000-000000000002', 'Nguyễn Văn An', 'Sinh viên năm 4 ĐH Sư phạm Hà Nội, chuyên Toán.', '3 năm dạy kèm Toán THCS/THPT, luyện thi vào 10.', 'FLEX', 1, 'APPROVED', now(), now(), '00000000-0000-0000-0000-000000000001'),
       ('00000000-0000-0000-0000-000000000003', 'Trần Thị Bình', 'Cử nhân Ngữ văn, yêu thích văn học hiện đại.', '2 năm dạy Văn THPT online.', 'ONLINE', 1, 'PENDING', now(), NULL, NULL)
ON CONFLICT (account_id) DO NOTHING;

INSERT INTO tutor_subjects (account_id, subject_code, level_code) VALUES
    ('00000000-0000-0000-0000-000000000002', 'TOAN', 'THCS'),
    ('00000000-0000-0000-0000-000000000002', 'TOAN', 'THPT'),
    ('00000000-0000-0000-0000-000000000002', 'ANH', 'THCS'),
    ('00000000-0000-0000-0000-000000000003', 'VAN', 'THPT'),
    ('00000000-0000-0000-0000-000000000003', 'VAN', 'LUYEN_THI')
ON CONFLICT DO NOTHING;

INSERT INTO tutor_areas (account_id, area_code) VALUES
    ('00000000-0000-0000-0000-000000000002', 'HN-CG'),
    ('00000000-0000-0000-0000-000000000002', 'HN-DD')
ON CONFLICT DO NOTHING;

INSERT INTO approval_history (account_id, action, reason, actor_id)
SELECT '00000000-0000-0000-0000-000000000003', 'SUBMIT', NULL, '00000000-0000-0000-0000-000000000003'
WHERE NOT EXISTS (SELECT 1 FROM approval_history WHERE account_id = '00000000-0000-0000-0000-000000000003');
INSERT INTO approval_history (account_id, action, reason, actor_id)
SELECT '00000000-0000-0000-0000-000000000002', 'APPROVE', 'Dữ liệu demo', '00000000-0000-0000-0000-000000000001'
WHERE NOT EXISTS (SELECT 1 FROM approval_history WHERE account_id = '00000000-0000-0000-0000-000000000002');

-- P12: tutor1 is public only with an approved teaching video.
INSERT INTO tutor_videos (id, account_id, url, status, reviewed_by, reviewed_at)
SELECT '00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000002', 'https://www.youtube.com/watch?v=aqz-KE-bpKQ',
       'APPROVED', '00000000-0000-0000-0000-000000000001', now()
WHERE EXISTS (SELECT 1 FROM tutor_profiles WHERE account_id = '00000000-0000-0000-0000-000000000002')
ON CONFLICT (id) DO NOTHING;
