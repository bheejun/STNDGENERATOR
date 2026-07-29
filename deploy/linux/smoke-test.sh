#!/usr/bin/env bash
set -euo pipefail

base_url="http://127.0.0.1:18180"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

curl --fail --silent --show-error "$base_url/api/projects" > "$work_dir/projects.json"
project_id="$(python3 - "$work_dir/projects.json" <<'PY'
import json, sys
items = json.load(open(sys.argv[1], encoding="utf-8"))
if not items:
    raise SystemExit("No migrated project found")
print(items[0]["projectId"])
PY
)"

curl --fail --silent --show-error \
  "$base_url/api/projects/$project_id/artifacts/exe" \
  --output "$work_dir/patch.exe"
curl --fail --silent --show-error \
  "$base_url/api/projects/$project_id/artifacts/delete-exe" \
  --output "$work_dir/delete.exe"

python3 - "$project_id" "$work_dir/patch.exe" "$work_dir/delete.exe" <<'PY'
from pathlib import Path
import sys

for path in map(Path, sys.argv[2:]):
    data = path.read_bytes()
    if len(data) < 1024 or data[:2] != b"MZ":
        raise SystemExit(f"Invalid Windows executable: {path} ({len(data)} bytes)")
    print(f"{path.name.upper()}_SIZE={len(data)}")
print(f"PROJECT_ID={sys.argv[1]}")
PY
