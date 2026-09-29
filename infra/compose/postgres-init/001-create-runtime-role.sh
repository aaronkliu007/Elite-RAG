#!/bin/sh
set -eu

: "${RAG_DB_RUNTIME_USER:?RAG_DB_RUNTIME_USER is required}"
: "${RAG_DB_RUNTIME_PASSWORD:?RAG_DB_RUNTIME_PASSWORD is required}"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set=runtime_user="$RAG_DB_RUNTIME_USER" \
  --set=runtime_password="$RAG_DB_RUNTIME_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'runtime_user', :'runtime_password') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'runtime_user') \gexec
SELECT format('ALTER DATABASE %I SET rag.runtime_role = %L', current_database(), :'runtime_user') \gexec
SQL
