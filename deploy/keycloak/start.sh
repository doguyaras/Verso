#!/usr/bin/env bash
# Starts the bundled demo IdP (ADR-0005, ADR-0010). Secrets are read from /run/secrets into variables of this process
# only: never the compose environment, never an argument list. Keycloak resolves ${VERSO_*} in the realm file from
# them at import; the import runs once (an existing realm is kept), so changing a secret later needs a new volume or
# the admin console.
set -euo pipefail
SECRETS="${VERSO_SECRETS_DIR:-/run/secrets}"
read_secret() {
  if [ ! -r "$SECRETS/$1" ] || [ ! -s "$SECRETS/$1" ]; then
    echo "keycloak-start: secret $1 missing, empty or unreadable" >&2
    exit 1
  fi
  cat "$SECRETS/$1"
}
KC_DB_PASSWORD="$(read_secret SECRET_DB_KEYCLOAK_PASSWORD)"
KC_BOOTSTRAP_ADMIN_PASSWORD="$(read_secret SECRET_KEYCLOAK_ADMIN_PASSWORD)"
VERSO_CI_CLIENT_SECRET="$(read_secret SECRET_KEYCLOAK_CI_CLIENT_SECRET)"
VERSO_DEMO_USER_PASSWORD="$(read_secret SECRET_KEYCLOAK_DEMO_USER_PASSWORD)"
VERSO_PANEL_ADMIN_PASSWORD="$(read_secret SECRET_KEYCLOAK_PANEL_ADMIN_PASSWORD)"
export KC_DB_PASSWORD KC_BOOTSTRAP_ADMIN_PASSWORD VERSO_CI_CLIENT_SECRET VERSO_DEMO_USER_PASSWORD VERSO_PANEL_ADMIN_PASSWORD
exec /opt/keycloak/bin/kc.sh start --import-realm
