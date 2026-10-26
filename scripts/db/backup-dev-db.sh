#!/usr/bin/env bash
# Backs up the LOCAL SYNTHETIC dev database (docker compose service "mandi-postgres", database mandi_demo).
# Output: backups/mandi_demo_<UTC timestamp>.dump (custom format) + .sha256 + .fingerprint.txt. Never overwrites a file.
# The backups/ folder is git-ignored. Never point this at any non-synthetic database.
set -euo pipefail
cd "$(dirname "$0")/../.."
CONTAINER="${PG_CONTAINER:-mandi-postgres}"; DB="${PG_DB:-mandi_demo}"; USER_="${PG_ADMIN_USER:-mandi}"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"; out="backups/${DB}_${stamp}.dump"
[ -e "$out" ] && { echo "refusing to overwrite $out" >&2; exit 1; }
mkdir -p backups
docker exec "$CONTAINER" pg_dump -U "$USER_" -d "$DB" -Fc > "$out"
docker exec -i "$CONTAINER" pg_restore --list < "$out" > /dev/null   # the archive must be readable
sha256sum "$out" > "$out.sha256"
docker exec -i "$CONTAINER" psql -U "$USER_" -d "$DB" -X -A -t -v ON_ERROR_STOP=1 < scripts/db/fingerprint.sql > "$out.fingerprint.txt"
echo "backup: $out ($(wc -c < "$out") bytes)"; echo "fingerprint: $out.fingerprint.txt"
