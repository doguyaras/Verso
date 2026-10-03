#!/usr/bin/env bash
# End-to-end check of the observability profile (ADR-0014) on the running stack:
#   - Prometheus scrapes the application (up == 1), knows the Verso metrics and loaded the five alert rules;
#   - Alertmanager is ready and Prometheus talks to it;
#   - Grafana is up with both provisioned datasources healthy and the Verso dashboard;
#   - Loki holds the application's log lines (through Alloy), labelled by service and level;
#   - privacy (llm-rules 2.1): no log line in Loki carries the questions scripts/qa-smoke.sh asked.
#
#   bash scripts/obs-smoke.sh   # after: docker compose --profile obs up -d --build --wait (and the other smokes)
#
# Prints counts and names only; the Grafana password is read from secrets/ and never printed.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export MSYS_NO_PATHCONV=1
GRAFANA="http://localhost:${VERSO_GRAFANA_PORT:-3000}"
TIMEOUT="${OBS_SMOKE_TIMEOUT:-180}"
die() { echo "obs-smoke: FAIL $1" >&2; exit 1; }

# Prometheus has no published port: its HTTP API is asked from inside its own container (busybox wget).
prom() {
  local q; q="$(node -e 'console.log(encodeURIComponent(process.argv[1]))' "$1")"   # "+" would be a space in a form
  docker compose exec -T prometheus wget -q -O - --post-data "query=$q" http://127.0.0.1:9090/api/v1/query
}
# Grafana's datasource proxy reaches Loki; the credentials go through a header read from stdin, never the argument list.
grafana() {
  local auth; auth="$(printf 'admin:%s' "$(cat secrets/SECRET_GRAFANA_ADMIN_PASSWORD)" | base64 | tr -d '\n')"
  curl --silent --show-error --max-time 15 --header @- "$GRAFANA$1" <<<"Authorization: Basic $auth"
}
count() { node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const j=JSON.parse(s);$1})"; }

deadline=$(( $(date +%s) + TIMEOUT ))
until [ "$(prom 'up{job="verso"}' | count 'console.log((j.data.result[0]||{value:[0,"0"]}).value[1])')" = 1 ]; do
  [ "$(date +%s)" -lt "$deadline" ] || die "Prometheus does not scrape the application (up{job=verso} != 1)"
  sleep 5
done
echo "obs-smoke: Prometheus scrapes the application"
series="$(prom 'count({__name__=~"verso_.+"})' | count 'console.log((j.data.result[0]||{value:[0,"0"]}).value[1])')"
[ "${series:-0}" -ge 8 ] || die "too few Verso series in Prometheus ($series)"
rules="$(docker compose exec -T prometheus wget -q -O - http://127.0.0.1:9090/api/v1/rules \
  | count 'console.log(j.data.groups.flatMap(g=>g.rules).filter(r=>r.type==="alerting").length)')"
[ "$rules" = 6 ] || die "expected 6 alert rules, Prometheus has $rules"
ams="$(docker compose exec -T prometheus wget -q -O - http://127.0.0.1:9090/api/v1/alertmanagers \
  | count 'console.log(j.data.activeAlertmanagers.length)')"
[ "$ams" -ge 1 ] || die "Prometheus has no active Alertmanager"
# The alert rules' unit tests: each alert fires in its outage and stays quiet otherwise (phase 7 review T2).
docker compose exec -T -w /etc/prometheus prometheus promtool test rules alerts.test.yml >/dev/null \
  || die "promtool test rules alerts.test.yml failed"
echo "obs-smoke: $series Verso series, $rules alert rules (unit tests pass), $ams Alertmanager"

[ "$(grafana /api/health | count 'console.log(j.database)')" = ok ] || die "Grafana is not healthy"
for uid in verso-prometheus verso-loki; do
  status="$(grafana "/api/datasources/uid/$uid/health" | count 'console.log(j.status)')"
  [ "$status" = OK ] || die "datasource $uid: $status"
done
[ "$(grafana '/api/dashboards/uid/verso-overview' | count 'console.log(j.dashboard.uid)')" = verso-overview ] \
  || die "the Verso dashboard is not provisioned"
echo "obs-smoke: Grafana healthy, datasources OK, dashboard present"

loki() { # loki <LogQL>: number of matching lines in the last hour
  local q; q="$(node -e 'console.log(encodeURIComponent(process.argv[1]))' "$1")"
  grafana "/api/datasources/proxy/uid/verso-loki/loki/api/v1/query_range?query=$q&since=1h&limit=5000" \
    | count 'console.log(j.data.result.reduce((n,s)=>n+s.values.length,0))'
}
until [ "$(loki '{service="verso-app"}')" -gt 0 ]; do
  [ "$(date +%s)" -lt "$deadline" ] || die "no application log line reached Loki"
  sleep 5
done
lines="$(loki '{service="verso-app"}')"
leveled="$(loki '{service="verso-app", level=~".+"}')"
[ "$leveled" -gt 0 ] || die "application log lines carry no level label"
echo "obs-smoke: Loki holds $lines application lines ($leveled with a level label)"
# Positive control: the lines of qa-smoke's questions did arrive (otherwise "no question text" would prove nothing).
answered="$(loki '{service="verso-app"} |= "Question answered"')"
[ "$answered" -gt 0 ] || die "qa-smoke's question log lines did not reach Loki (run scripts/qa-smoke.sh first)"
for phrase in "annual leave" "planet has the most moons" "smoke.pdf" "leave policy"; do
  hits="$(loki "{service=~\".+\"} |~ \"(?i)$phrase\"")"
  [ "$hits" = 0 ] || die "a question, a file name or document text reached the logs ($hits lines)"
done
echo "obs-smoke: $answered question lines, none with a question, file name or document text"
# Only the allow-listed services ship logs, labelled by service and level (never the trace id).
labels="$(grafana "/api/datasources/proxy/uid/verso-loki/loki/api/v1/labels?since=1h" | count 'console.log(j.data.sort().join(" "))')"
services="$(grafana "/api/datasources/proxy/uid/verso-loki/loki/api/v1/label/service/values?since=1h" | count 'console.log(j.data.sort().join(" "))')"
for label in $labels; do [ "$label" != trace_id ] || die "trace_id is a Loki label"; done
for s in $services; do
  case "$s" in verso-app|migrate|edge|backup) ;; *) die "logs of $s reached Loki (not allow-listed)" ;; esac
done
echo "obs-smoke: Loki labels [$labels], services [$services]"
echo "obs-smoke: OK"
