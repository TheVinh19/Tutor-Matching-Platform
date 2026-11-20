# Deletes everything except the demo seed data: accounts not in the 5 demo ids, their profiles, plans, sessions,
# availability, reviews and notices. Run from infra/:  powershell -File postgres/clean-non-demo.ps1
# Needs the stack running (postgres container). Irreversible.
$demo = "'00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000005'"
$seedPlan = "'10000000-0000-0000-0000-000000000001'"

function Run($db, $sql) {
  Write-Host "== $db"
  docker compose --env-file ../.env exec -T postgres psql -U postgres -d $db -v ON_ERROR_STOP=1 -c $sql
}

Run account_db "DELETE FROM accounts WHERE id NOT IN ($demo);"
Run tutor_db "DELETE FROM reviews WHERE plan_id <> $seedPlan; DELETE FROM tutor_profiles WHERE account_id NOT IN ($demo);"
Run learning_db "DELETE FROM sessions WHERE plan_id <> $seedPlan; DELETE FROM plan_preferred_slots WHERE plan_id <> $seedPlan; DELETE FROM plans WHERE id <> $seedPlan; DELETE FROM availability_slots WHERE tutor_id NOT IN ($demo);"
Run notification_db "DELETE FROM notifications WHERE recipient_id NOT IN ($demo) OR (ref_id IS NOT NULL AND ref_id <> $seedPlan);"
