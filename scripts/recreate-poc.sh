#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
TF_DIR="${PROJECT_ROOT}/infra/terraform"

AUTO_APPROVE="${AUTO_APPROVE:-false}"
RUN_SEED="${RUN_SEED:-true}"
PRODUCT_COUNT="${PRODUCT_COUNT:-50000}"
RESET_PRODUCTS="${RESET_PRODUCTS:-false}"
WARM_PERCENT="${WARM_PERCENT:-}"
OPEN_TUNNEL="${OPEN_TUNNEL:-true}"
SSH_KEY="${SSH_KEY:-${PROJECT_ROOT}/.ssh/poc_coherence_ed25519}"

log() {
  printf '\n==> %s\n' "$*"
}

fail() {
  printf 'FAIL %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Command not found: $1"
}

require_file() {
  [[ -f "$1" ]] || fail "Required file not found: $1"
}

wait_for_cmd() {
  local label="$1"
  shift
  local attempt
  for attempt in {1..60}; do
    if "$@" >/dev/null 2>&1; then
      printf 'OK  %s\n' "$label"
      return
    fi
    sleep 5
  done
  fail "Timed out waiting for ${label}"
}

tf_output() {
  terraform -chdir="$TF_DIR" output -raw "$1"
}

case "$AUTO_APPROVE" in true | false) ;; *) fail "AUTO_APPROVE must be true or false" ;; esac
case "$RUN_SEED" in true | false) ;; *) fail "RUN_SEED must be true or false" ;; esac
case "$RESET_PRODUCTS" in true | false) ;; *) fail "RESET_PRODUCTS must be true or false" ;; esac
case "$OPEN_TUNNEL" in true | false) ;; *) fail "OPEN_TUNNEL must be true or false" ;; esac
if ! [[ "$PRODUCT_COUNT" =~ ^[1-9][0-9]*$ ]]; then
  fail "PRODUCT_COUNT must be a positive integer"
fi
if [[ -n "$WARM_PERCENT" ]] && ! [[ "$WARM_PERCENT" =~ ^([0-9]|[1-9][0-9]|100)$ ]]; then
  fail "WARM_PERCENT must be empty or an integer from 0 to 100"
fi

log "Checking local prerequisites"
require_cmd terraform
require_cmd docker
require_cmd jq
require_cmd ssh
require_cmd scp
require_file "${TF_DIR}/poc.auto.tfvars"
require_file "${SSH_KEY}"
require_file "${SSH_KEY}.pub"
require_file "${PROJECT_ROOT}/.secrets/db_admin_password.txt"
require_file "${PROJECT_ROOT}/.secrets/app_db_password.txt"

log "Provisioning OCI infrastructure"
terraform -chdir="$TF_DIR" init
if [[ "$AUTO_APPROVE" == "true" ]]; then
  terraform -chdir="$TF_DIR" apply -auto-approve
else
  terraform -chdir="$TF_DIR" apply
fi

APP_PUBLIC_IP="$(tf_output app_bastion_public_ip)"
DB_PRIVATE_IP="$(tf_output db_private_ip)"
REDIS_PRIVATE_IP="$(tf_output redis_private_ip)"
COHERENCE_PRIVATE_IP="$(terraform -chdir="$TF_DIR" output -json coherence_private_ips | jq -r '.[0]')"

log "Waiting for SSH access"
wait_for_cmd "app-bastion SSH" "${SCRIPT_DIR}/ssh-bastion.sh" true
wait_for_cmd "DB SSH through bastion" "${SCRIPT_DIR}/ssh-private.sh" "$DB_PRIVATE_IP" true
wait_for_cmd "Redis SSH through bastion" "${SCRIPT_DIR}/ssh-private.sh" "$REDIS_PRIVATE_IP" true
wait_for_cmd "Coherence SSH through bastion" "${SCRIPT_DIR}/ssh-private.sh" "$COHERENCE_PRIVATE_IP" true

log "Configuring Oracle Database and demo schema"
"${SCRIPT_DIR}/setup-oracle-demo-db.sh"

if [[ "$RUN_SEED" == "true" ]]; then
  log "Seeding product catalog"
  PRODUCT_COUNT="$PRODUCT_COUNT" RESET_PRODUCTS="$RESET_PRODUCTS" "${SCRIPT_DIR}/seed-oracle-products.sh"
else
  log "Skipping product seed"
fi

log "Building application JAR with Docker/Maven"
docker run --rm -v "${PROJECT_ROOT}/app":/workspace -w /workspace \
  maven:3.9.11-eclipse-temurin-17 mvn -q -DskipTests package

log "Deploying app, Redis tuning and Coherence storage node"
"${SCRIPT_DIR}/deploy-coherence-app.sh"

log "Waiting for app health"
wait_for_cmd "app health" "${SCRIPT_DIR}/ssh-bastion.sh" "curl -fsS http://127.0.0.1:8080/health"
"${SCRIPT_DIR}/ssh-bastion.sh" "curl -fsS http://127.0.0.1:8080/health"
printf '\n'

if [[ -n "$WARM_PERCENT" ]]; then
  log "Warming Redis and Coherence caches to ${WARM_PERCENT}%"
  "${SCRIPT_DIR}/ssh-bastion.sh" \
    "curl -fsS -X POST 'http://127.0.0.1:8080/cache/warm/redis?start=1&percent=${WARM_PERCENT}&total=${PRODUCT_COUNT}&clear=true&resetStats=true'"
  printf '\n'
  "${SCRIPT_DIR}/ssh-bastion.sh" \
    "curl -fsS -X POST 'http://127.0.0.1:8080/cache/warm/coherence?start=1&percent=${WARM_PERCENT}&total=${PRODUCT_COUNT}&clear=true&resetStats=true'"
  printf '\n'
fi

cat <<EOF

POC ready.

App bastion public IP: ${APP_PUBLIC_IP}
Console via tunnel:    http://localhost:8081/console
Observability:         http://localhost:8081/management-proxy/cluster
Coherence REST direct: http://localhost:30000/management/coherence/cluster

EOF

if [[ "$OPEN_TUNNEL" == "true" ]]; then
  log "Opening SSH tunnel. Press Ctrl+C to close it."
  exec "${SCRIPT_DIR}/tunnel-coherence-management.sh"
fi
