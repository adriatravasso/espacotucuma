#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="$repo_root/.env.demo"

random_hex() {
  od -An -N "$1" -tx1 /dev/urandom | tr -d ' \n'
}

random_base64_key() {
  head -c 32 /dev/urandom | base64 | tr -d '\n'
}

if [[ -f "$env_file" ]]; then
  if ! grep -q '^RUSTFS_ACCESS_KEY=' "$env_file" || ! grep -q '^RUSTFS_SECRET_KEY=' "$env_file"; then
    echo ".env.demo existe, mas não contém as credenciais RustFS esperadas; revise-a antes de continuar." >&2
    exit 1
  fi
  if ! grep -q '^RUSTFS_SSE_S3_MASTER_KEY=' "$env_file"; then
    printf 'RUSTFS_SSE_S3_MASTER_KEY=%s\n' "$(random_base64_key)" >>"$env_file"
    chmod 600 "$env_file"
    echo "Chave mestra SSE-S3 adicionada à configuração local existente em .env.demo."
  else
    echo "Configuração demo já existe em .env.demo (mantida)."
  fi
  exit 0
fi

umask 077
cat >"$env_file" <<EOF
SYNC_DATABASE_PASSWORD=$(random_hex 32)
TUCUMA_SYNC_SECRET=$(random_hex 32)
RUSTFS_ACCESS_KEY=root$(random_hex 8)
RUSTFS_SECRET_KEY=$(random_hex 32)
RUSTFS_SSE_S3_MASTER_KEY=$(random_base64_key)
TUCUMA_BACKUP_ACCESS_KEY=$(random_hex 10)
TUCUMA_BACKUP_SECRET_KEY=$(random_hex 32)
DEMO_SERVER_PORT=18081
EOF

chmod 600 "$env_file"
echo "Segredos aleatórios criados em .env.demo; o arquivo está excluído do Git. Use somente neste ambiente descartável."
