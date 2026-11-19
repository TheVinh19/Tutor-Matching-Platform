-- Demo data for the mock accounts (fixed UUIDs from identity DemoAccountSeeder). Dates are relative to the first run.
-- tutor1: Mon/Wed/Fri/Sat 08:00-13:00 and Tue/Thu 17:00-22:00 (two grid cells each). learner1: confirmed plan Tue/Thu 18:00-20:00. learner2: nothing.
INSERT INTO availability_slots (id, tutor_id, day_of_week, start_time, end_time, mode, effective_from)
SELECT ('20000000-0000-0000-0000-00000000000' || d)::uuid, '00000000-0000-0000-0000-000000000002', d,
       CASE WHEN d IN (2, 4) THEN time '17:00' ELSE time '08:00' END,
       CASE WHEN d IN (2, 4) THEN time '22:00' ELSE time '13:00' END,
       'FLEX', current_date - 30
FROM unnest(ARRAY[1, 2, 3, 4, 5, 6]) AS d
ON CONFLICT (id) DO UPDATE SET start_time = excluded.start_time, end_time = excluded.end_time;

INSERT INTO plans (id, learner_id, tutor_id, subject_code, level_code, mode, note, status, payment_status,
                   start_date, end_date, meet_link, created_at, proposed_at, confirmed_at)
VALUES ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000002',
        'TOAN', 'THPT', 'ONLINE', 'Ôn thi vào lớp 10', 'CONFIRMED', 'PAYMENT_PENDING_MOCK',
        current_date - 7, current_date + 20, 'https://meet.google.com/demo-giasu-001',
        now() - interval '9 days', now() - interval '9 days', now() - interval '8 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO sessions (id, plan_id, tutor_id, learner_id, start_at, end_at, block_until, status, meet_link, completed_at, confirmed_at)
SELECT gen_random_uuid(), '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000004',
       (d::date + time '18:00') AT TIME ZONE 'Asia/Ho_Chi_Minh',
       (d::date + time '20:00') AT TIME ZONE 'Asia/Ho_Chi_Minh',
       (d::date + time '21:00') AT TIME ZONE 'Asia/Ho_Chi_Minh',
       CASE WHEN d::date < current_date THEN 'CONFIRMED' ELSE 'SCHEDULED' END,
       'https://meet.google.com/demo-giasu-001',
       CASE WHEN d::date < current_date THEN (d::date + time '20:00') AT TIME ZONE 'Asia/Ho_Chi_Minh' END,
       CASE WHEN d::date < current_date THEN (d::date + time '21:00') AT TIME ZONE 'Asia/Ho_Chi_Minh' END
FROM generate_series(current_date - 7, current_date + 20, interval '1 day') AS d
WHERE extract(isodow FROM d) IN (2, 4)
  AND NOT EXISTS (SELECT 1 FROM sessions WHERE plan_id = '10000000-0000-0000-0000-000000000001');
