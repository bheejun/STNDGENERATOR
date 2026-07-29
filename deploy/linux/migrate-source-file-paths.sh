#!/usr/bin/env bash
set -euo pipefail

cd "$HOME/stnd-generator"

docker compose exec -T postgres psql \
  -v ON_ERROR_STOP=1 \
  -U postgres \
  -d csr <<'SQL'
SELECT id, project_id, stored_path
FROM source_file
ORDER BY id;

UPDATE source_file
SET stored_path = replace(
  replace(stored_path, $$C:\stndGenerator\data\$$, '/srv/data/'),
  $$\$$,
  '/'
)
WHERE strpos(stored_path, $$C:\stndGenerator\data\$$) = 1;

SELECT id, project_id, stored_path
FROM source_file
ORDER BY id;
SQL
