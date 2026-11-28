-- Databases of assessment-service (P16), care-service (P18) and ai-service (P17).
-- Idempotent: runs on a fresh volume (docker-entrypoint-initdb.d) and can be re-run by hand on an existing one, see README.
\getenv assessment_password ASSESSMENT_DB_PASSWORD
SELECT format('CREATE ROLE assessment_app LOGIN PASSWORD %L', :'assessment_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'assessment_app') \gexec
SELECT 'CREATE DATABASE assessment_db OWNER assessment_app' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'assessment_db') \gexec
REVOKE CONNECT ON DATABASE assessment_db FROM PUBLIC;
GRANT CONNECT ON DATABASE assessment_db TO assessment_app;
\getenv care_password CARE_DB_PASSWORD
SELECT format('CREATE ROLE care_app LOGIN PASSWORD %L', :'care_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'care_app') \gexec
SELECT 'CREATE DATABASE care_db OWNER care_app' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'care_db') \gexec
REVOKE CONNECT ON DATABASE care_db FROM PUBLIC;
GRANT CONNECT ON DATABASE care_db TO care_app;
\getenv ai_password AI_DB_PASSWORD
SELECT format('CREATE ROLE ai_app LOGIN PASSWORD %L', :'ai_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_app') \gexec
SELECT 'CREATE DATABASE ai_db OWNER ai_app' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'ai_db') \gexec
REVOKE CONNECT ON DATABASE ai_db FROM PUBLIC;
GRANT CONNECT ON DATABASE ai_db TO ai_app;
