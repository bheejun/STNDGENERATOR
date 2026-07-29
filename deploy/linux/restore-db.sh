#!/usr/bin/env bash
set -euo pipefail

cd "$HOME/stnd-generator"
docker compose up -d postgres

status=""
for _ in $(seq 1 30); do
  status="$(docker inspect --format='{{.State.Health.Status}}' stnd-generator-postgres-1 2>/dev/null || true)"
  echo "POSTGRES=$status"
  if [[ "$status" == "healthy" ]]; then
    break
  fi
  sleep 2
done

if [[ "$status" != "healthy" ]]; then
  echo "PostgreSQL did not become healthy" >&2
  exit 1
fi

docker compose exec -T postgres \
  pg_restore -U postgres -d csr --clean --if-exists --no-owner < backup/csr.dump

echo -n "PROJECT_COUNT="
docker compose exec -T postgres \
  psql -U postgres -d csr -Atc "select count(*) from build_project"
