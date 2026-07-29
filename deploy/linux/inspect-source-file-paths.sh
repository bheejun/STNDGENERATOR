#!/usr/bin/env bash
set -euo pipefail

cd "$HOME/stnd-generator"

docker compose exec -T postgres psql \
  -v ON_ERROR_STOP=1 \
  -U postgres \
  -d csr <<'SQL'
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name LIKE '%source%'
ORDER BY table_name;

SELECT table_name, column_name, data_type
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name LIKE '%source%'
ORDER BY table_name, ordinal_position;
SQL
