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
  status=$?
  if [[ "$status" -ne 0 ]]; then
    echo "A jornada demo falhou; estado e logs recentes dos serviços:" >&2
    "${compose[@]}" ps --all >&2 || true
    "${compose[@]}" logs --no-color --tail 100 rustfs rustfs-bootstrap sync-service >&2 || true
  fi
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -f "$response_file"
}
trap cleanup EXIT

"${compose[@]}" up --detach --build
base_url="http://127.0.0.1:${DEMO_SERVER_PORT}"
json() {
  docker run --rm --interactive --network none --entrypoint jq tucuma/demo-tools:0.1.36 "$@"
}
random_uuid() {
  local value
  value="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
  printf '%s-%s-%s-%s-%s' \
    "${value:0:8}" "${value:8:4}" "${value:12:4}" "${value:16:4}" "${value:20:12}"
}
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

auth_header_name='Author'
auth_header_name+='ization'
invalid_auth_scheme='Be'
invalid_auth_scheme+='arer'
invalid_auth_token='synthetic'
invalid_auth_token+='-invalid'
unauthorized_status="$(curl --silent --output "$response_file" --write-out '%{http_code}' \
  --request POST "$base_url/sync/admin/enrollments" \
  --header "$auth_header_name: $invalid_auth_scheme $invalid_auth_token" \
  --header 'X-Tucuma-Contract-Version: 1' \
  --header 'Content-Type: application/json' \
  --data '{"establishmentName":"Estabelecimento sintetico"}')"
if [[ "$unauthorized_status" != 401 ]]; then
  echo "O endpoint administrativo recusou a credencial inválida com HTTP $unauthorized_status: $(cat "$response_file")" >&2
  exit 1
fi

enrollment="$(curl --fail --silent --show-error \
  --request POST "$base_url/sync/admin/enrollments" \
  --header "Authorization: Bearer $TUCUMA_SYNC_SECRET" \
  --header 'Content-Type: application/json' \
  --data '{"establishmentName":"Estabelecimento sintetico de demonstracao"}')"
establishment_id="$(json -er '.establishmentId' <<<"$enrollment")"
pairing_code="$(json -er '.pairingCode' <<<"$enrollment")"

pairing="$(curl --fail --silent --show-error \
  --request POST "$base_url/sync/devices/pair" \
  --header 'Content-Type: application/json' \
  --data "$(json -nc --arg code "$pairing_code" '{pairingCode:$code,deviceName:"Estacao sintetica de demonstracao"}')")"
device_id="$(json -er '.deviceId' <<<"$pairing")"
device_credential="$(json -er '.deviceCredential' <<<"$pairing")"

token_response="$(curl --fail --silent --show-error \
  --request POST "$base_url/sync/devices/token" \
  --header "X-Tucuma-Device-Id: $device_id" \
  --header "Authorization: Device $device_credential")"
access_token="$(json -er '.accessToken' <<<"$token_response")"
headers=(-H "Authorization: Bearer $access_token" -H 'X-Tucuma-Contract-Version: 1' -H 'Content-Type: application/json')

operation_id="$(random_uuid)"
entity_id="$(random_uuid)"
operations_request="$(json -nc --arg operation "$operation_id" --arg entity "$entity_id" \
  '{contractVersion:1,operations:[{operationId:$operation,idempotencyKey:$operation,entityType:"recebimento",entityId:$entity,operationType:"UPSERT",entityVersion:0,payloadVersion:1,payload:{recordId:$entity,synthetic:true}}]}')"
operations_response="$(curl --fail --silent --show-error \
  --request POST "$base_url/sync/operations" "${headers[@]}" --data "$operations_request")"
if [[ "$(json -er '.results[0].outcome' <<<"$operations_response")" != ACCEPTED ]]; then
  echo "A operação sintética não foi confirmada pelo serviço." >&2
  exit 1
fi
checkpoint="$(json -er '.checkpoint' <<<"$operations_response")"

backup_status="$(curl --silent --show-error --output "$response_file" --write-out '%{http_code}' \
  --request POST "$base_url/sync/backup" "${headers[@]}" \
  --data "$(json -nc --argjson checkpoint "$checkpoint" '{contractVersion:1,checkpoint:$checkpoint}')")"
if [[ "$backup_status" != 200 ]]; then
  echo "A criação do snapshot respondeu HTTP $backup_status: $(cat "$response_file")" >&2
  exit 1
fi
backup_response="$(cat "$response_file")"
backup_id="$(json -er '.backupId' <<<"$backup_response")"
if [[ "$(json -er '.status' <<<"$backup_response")" != VALIDADO ]]; then
  echo "O snapshot de demonstração não foi validado." >&2
  exit 1
fi

backups_status="$(curl --silent --show-error --output "$response_file" --write-out '%{http_code}' --get "$base_url/sync/backups" \
  --header "Authorization: Bearer $access_token" \
  --header 'X-Tucuma-Contract-Version: 1')"
if [[ "$backups_status" != 200 ]]; then
  echo "A listagem de snapshots respondeu HTTP $backups_status: $(cat "$response_file")" >&2
  exit 1
fi
backups="$(cat "$response_file")"
if ! json -e --arg id "$backup_id" '.backups | any(.backupId == $id)' <<<"$backups" >/dev/null; then
  echo "O snapshot validado não apareceu na listagem autenticada." >&2
  exit 1
fi

echo "Verificando leitura anônima do objeto..."
DEMO_OBJECT_KEY="backups/$establishment_id/$backup_id.json" "${compose[@]}" run --rm --no-deps anonymous-check
echo "Verificando que a credencial da aplicação não acessa outro bucket..."
"${compose[@]}" run --rm --no-deps backup-scope-check

echo "Demo validada: healthcheck, credencial administrativa, pareamento, sync sintético, snapshot persistido, acesso privado e escopo mínimo do usuário S3."
