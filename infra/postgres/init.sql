\getenv account_password ACCOUNT_DB_PASSWORD
SELECT format('CREATE ROLE account_app LOGIN PASSWORD %L', :'account_password') \gexec
CREATE DATABASE account_db OWNER account_app;
REVOKE CONNECT ON DATABASE account_db FROM PUBLIC;
GRANT CONNECT ON DATABASE account_db TO account_app;
\getenv tutor_password TUTOR_DB_PASSWORD
SELECT format('CREATE ROLE tutor_app LOGIN PASSWORD %L', :'tutor_password') \gexec
CREATE DATABASE tutor_db OWNER tutor_app;
REVOKE CONNECT ON DATABASE tutor_db FROM PUBLIC;
GRANT CONNECT ON DATABASE tutor_db TO tutor_app;
\getenv learning_password LEARNING_DB_PASSWORD
SELECT format('CREATE ROLE learning_app LOGIN PASSWORD %L', :'learning_password') \gexec
CREATE DATABASE learning_db OWNER learning_app;
REVOKE CONNECT ON DATABASE learning_db FROM PUBLIC;
GRANT CONNECT ON DATABASE learning_db TO learning_app;
\getenv payment_password PAYMENT_DB_PASSWORD
SELECT format('CREATE ROLE payment_app LOGIN PASSWORD %L', :'payment_password') \gexec
CREATE DATABASE payment_db OWNER payment_app;
REVOKE CONNECT ON DATABASE payment_db FROM PUBLIC;
GRANT CONNECT ON DATABASE payment_db TO payment_app;
\getenv notification_password NOTIFICATION_DB_PASSWORD
SELECT format('CREATE ROLE notification_app LOGIN PASSWORD %L', :'notification_password') \gexec
CREATE DATABASE notification_db OWNER notification_app;
REVOKE CONNECT ON DATABASE notification_db FROM PUBLIC;
GRANT CONNECT ON DATABASE notification_db TO notification_app;
