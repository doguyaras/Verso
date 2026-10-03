#!/usr/bin/env bash
# Five-minute demo on the running stack (phase 9): uploads the synthetic sample documents (samples/*.pdf, a fictional
# company), waits until they are processed, asks three questions and one that the documents cannot answer, and shows
# the answers with their sources and the mode header. The uploads stay (they belong to the CI client's account, not
# to the panel's demo user); remove them with: bash scripts/demo.sh --clean
#
#   bash scripts/dev-secrets.sh && docker compose up -d --build --wait && bash scripts/demo.sh
#
# Uses the CI client of the bundled Keycloak (client credentials). Answers are printed here, on your terminal; the
# application never logs them (llm-rules 2.1). A CPU-only host takes tens of seconds per answer.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"   # relative paths: a native Windows curl (Git Bash) cannot open /c/... paths
IDP="http://localhost:${VERSO_KEYCLOAK_PORT:-8180}/realms/verso/protocol/openid-connect"
API="http://localhost:${VERSO_HTTP_PORT:-8080}"
command -v node >/dev/null || { echo "demo: needs node (JSON formatting)" >&2; exit 3; }

token() {
  curl --silent --show-error --max-time 10 "$IDP/token" --data grant_type=client_credentials --data client_id=verso-ci \
    --data-urlencode "client_secret@secrets/SECRET_KEYCLOAK_CI_CLIENT_SECRET" \
    | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{let t;try{t=JSON.parse(s).access_token}catch{}
        if(!t){console.error('demo: no token from Keycloak (is the stack up? docker compose ps)');process.exit(1)}console.log(t)})"
}
call() { local t; t="$(token)" || exit 1; curl --silent --max-time 180 --header @- "$@" <<<"Authorization: Bearer $t"; }
# One document per line, tab-separated: id, status, file name (last, so a name with spaces stays whole).
ids() { call "$API/v1/documents?size=100" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{for(const d of JSON.parse(s).data)console.log(d.id+'\t'+d.status+'\t'+d.fileName)})"; }

if [ "${1:-}" = --clean ]; then
  ids | while IFS=$'\t' read -r id _ name; do call -X DELETE "$API/v1/documents/$id" >/dev/null; echo "demo: deleted $name"; done
  exit 0
fi

echo "== Verso demo: $(curl --silent --output /dev/null --dump-header - "$API/v1/documents" | tr -d '\r' | sed -n 's/^[Xx]-[Rr]ag-[Mm]ode: *//p') mode =="
trap 'rm -f demo-question.tmp' EXIT
existing="$(ids | cut -f3)"
for pdf in samples/*.pdf; do
  name="$(basename "$pdf")"
  if printf '%s\n' "$existing" | grep -qx "$name"; then echo "demo: $name already uploaded"; continue; fi
  call --output /dev/null --form "file=@$pdf;type=application/pdf" "$API/v1/documents"
  echo "demo: uploaded $name"
done

printf 'demo: processing'
for _ in $(seq 1 120); do
  pending="$(ids | cut -f2 | grep -cEv '^(READY|FAILED)$' || true)"
  [ "$pending" = 0 ] && break
  printf '.'; sleep 3
done
echo; ids | awk -F'\t' '{print "  " $2 "  " $3}'

ask() {
  echo; echo "Soru: $1"
  local started=$SECONDS
  # The body from a UTF-8 file: a native Windows curl re-encodes Turkish letters read from stdin or arguments.
  node -e 'require("fs").writeFileSync("demo-question.tmp", JSON.stringify({ question: process.argv[1] }))' "$1"
  call --header 'Content-Type: application/json' --data-binary @demo-question.tmp "$API/v1/questions" \
    | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const j=JSON.parse(s);
        if(j.error){console.log('  Hata '+j.error.code+': '+j.error.message);return;}
        console.log('  Cevap: '+j.answer);
        for(const c of j.citations)console.log('  ['+c.number+'] '+c.fileName+', sayfa '+c.page);
        console.log('  ('+(j.found?'kaynaklı':'kaynak yok')+', '+j.mode+' / '+j.model+')');})"
  echo "  süre: $((SECONDS - started)) sn"
}
ask "Beş yıldan az hizmeti olan bir çalışan yılda kaç gün yıllık izin kullanır?"
ask "Şirket laptopu kaybolursa ne kadar süre içinde bildirmem gerekir?"
ask "30.000 TL'lik bir masrafı kim onaylar?"
ask "Şirketin borsa kodu nedir?"
echo; echo "demo: panel http://localhost:${VERSO_HTTP_PORT:-8080}/panel/ : kullanıcı demo, parola secrets/SECRET_KEYCLOAK_DEMO_USER_PASSWORD."
echo "demo: belgeler hesaba aittir; bu betiğin yükledikleri CI istemcisinin hesabındadır. Panelde samples/ PDF'lerini sürükleyip bırakın."
