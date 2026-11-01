#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  echo "Docker Engine e Docker Compose v2 são necessários." >&2
  exit 1
fi

./scripts/configure-demo-environment.sh
if [[ "${TUCUMA_DEMO_SKIP_MAVEN_BUILD:-false}" != "true" ]]; then
  ./mvnw --batch-mode --no-transfer-progress -pl sync-service -am package
fi

set -a
# .env.demo is generated locally and contains only disposable synthetic credentials.
source .env.demo
set +a

project="tucuma-demo-smoke-$(date +%s)-$$"
compose=(docker compose --project-name "$project" --env-file .env.demo -f compose.yaml)
response_file="$(mktemp)"
cleanup() {
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -f "$response_file"
}
trap cleanup EXIT

"${compose[@]}" up --detach --build
base_url="http://127.0.0.1:${DEMO_SERVER_PORT}"
ready=false
for attempt in $(seq 1 90); do
  if curl --fail --silent "$base_url/actuator/health/readiness" >/dev/null; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  echo "Healthcheck do serviço não ficou pronto." >&2
  exit 1
fi

published_address="$("${compose[@]}" port sync-service 8080)"
if [[ "$published_address" != 127.0.0.1:* ]]; then
  echo "A API demo precisa ficar publicada somente em loopback; encontrado: $published_address" >&2
  exit 1
fi

unauthorized_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --request POST "$base_url/sync/admin/enrollments" \
  --header 'Authorization: Bearer credencial-demo-invalida' \
  --header 'Content-Type: application/json' \
  --data '{"establishmentName":"Estabelecimento sintético"}')"
if [[ "$unauthorized_status" != 401 ]]; then
  echo "O endpoint administrativo aceitou credencial demo inválida: HTTP $unauthorized_status." >&2
  exit 1
fi

enrollment="$(curl --fail --silent --show-error \
  --request POST "$base_url/sync/admin/enrollments" \
  --header "Authorization: Bearer $TUCUMA_SYNC_SECRET" \
  --header 'Content-Type: application/json' \
  --data '{"establishmentName":"Estabelecimento sintético de demonstração"}')"
establishment_id="$(jq -er '.establishmentId' <<<"$enrollment")"
pairing_code="$(jq -er '.pairingCode' <<<"$enrollment")"

pairing="$(curl --fail --silent --show-error \
  --request POST "$base_url/sync/devices/pair" \
  --header 'Content-Type: application/json' \
  --data "$(jq -nc --arg code "$pairing_code" '{pairingCode:$code,deviceName:"Estação sintética de demonstração"}')")"
device_id="$(jq -er '.deviceId' <<<"$pairing")"
device_credential="$(jq -er '.deviceCredential' <<<"$pairing")"

token_response="$(curl --fail --silent --show-error \
  --request POST "$base_url/sync/devices/token" \
  --header "X-Tucuma-Device-Id: $device_id" \
  --header "Authorization: Device $device_credential")"
access_token="$(jq -er '.accessToken' <<<"$token_response")"
headers=(-H "Authorization: Bearer $access_token" -H 'X-Tucuma-Contract-Version: 1' -H 'Content-Type: application/json')

operation_id="$(cat /proc/sys/kernel/random/uuid)"
entity_id="$(cat /proc/sys/kernel/random/uuid)"
operations_request="$(jq -nc --arg operation "$operation_id" --arg entity "$entity_id" \
  '{contractVersion:1,operations:[{operationId:$operation,idempotencyKey:$operation,entityType:"recebimento",entityId:$entity,operationType:"UPSERT",entityVersion:0,payloadVersion:1,payload:{recordId:$entity,synthetic:true}}]}')"
operations_response="$(curl --fail --silent --show-error \
  --request POST "$base_url/sync/operations" "${headers[@]}" --data "$operations_request")"
if [[ "$(jq -er '.results[0].outcome' <<<"$operations_response")" != ACCEPTED ]]; then
  echo "A operação sintética não foi confirmada pelo serviço." >&2
  exit 1
fi
checkpoint="$(jq -er '.checkpoint' <<<"$operations_response")"

backup_response="$(curl --fail --silent --show-error \
  --request POST "$base_url/sync/backup" "${headers[@]}" \
  --data "$(jq -nc --argjson checkpoint "$checkpoint" '{contractVersion:1,checkpoint:$checkpoint}')")"
backup_id="$(jq -er '.backupId' <<<"$backup_response")"
if [[ "$(jq -er '.status' <<<"$backup_response")" != VALIDADO ]]; then
  echo "O snapshot de demonstração não foi validado." >&2
  exit 1
fi

backups="$(curl --fail --silent --show-error --get "$base_url/sync/backups" "${headers[@]::2}")"
if ! jq -e --arg id "$backup_id" '.backups | any(.backupId == $id)' <<<"$backups" >/dev/null; then
  echo "O snapshot validado não apareceu na listagem autenticada." >&2
  exit 1
fi

DEMO_OBJECT_KEY="backups/$establishment_id/$backup_id.json" "${compose[@]}" run --rm --no-deps anonymous-check
"${compose[@]}" run --rm --no-deps backup-scope-check

echo "Demo validada: healthcheck, credencial administrativa, pareamento, sync sintético, snapshot persistido, acesso privado e escopo mínimo do usuário S3."
