#!/usr/bin/env bash
# Re-imports the demo realm (deploy/keycloak/realm/verso-realm.json) into the bundled Keycloak. Keycloak imports a
# realm only once, so a stack created before a realm change (phase 10: the panel's client and the operator role)
# keeps the old realm. This drops the demo IdP's own database "keycloak" and lets Keycloak import the file again.
# Users, sessions and consents of the demo realm are lost; Verso's documents (database "verso") are not touched.
#
#   bash scripts/keycloak-reimport.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export MSYS_NO_PATHCONV=1   # Git Bash on Windows would rewrite the container paths below
docker compose stop keycloak >/dev/null
docker compose exec -T -u postgres postgres psql -X -q -v ON_ERROR_STOP=1 -d verso \
  -c "DROP DATABASE IF EXISTS keycloak WITH (FORCE)" >/dev/null
docker compose exec -T -u postgres postgres bash /docker-entrypoint-initdb.d/30-keycloak.sh
# "start", not "up": the existing container keeps whatever compose files and overlays created it.
docker compose start keycloak >/dev/null
container="$(docker compose ps -a -q keycloak)"
for _ in $(seq 1 90); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$container")" = healthy ] && break
  sleep 3
done
[ "$(docker inspect -f '{{.State.Health.Status}}' "$container")" = healthy ] \
  || { echo "keycloak-reimport: keycloak did not become healthy" >&2; exit 1; }
echo "keycloak-reimport: realm verso imported again"
