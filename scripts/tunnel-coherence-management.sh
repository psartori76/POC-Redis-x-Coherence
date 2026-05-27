#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
TF_DIR="${PROJECT_ROOT}/infra/terraform"

BASTION_IP="${BASTION_IP:-$(terraform -chdir="${TF_DIR}" output -raw app_bastion_public_ip)}"
COHERENCE_NODE_IP="${COHERENCE_NODE_IP:-$(terraform -chdir="${TF_DIR}" output -json coherence_private_ips | jq -r '.[0]')}"
LOCAL_MGMT_PORT="${LOCAL_MGMT_PORT:-30000}"
LOCAL_APP_PORT="${LOCAL_APP_PORT:-8081}"
SSH_KEY="${SSH_KEY:-${PROJECT_ROOT}/.ssh/poc_coherence_ed25519}"

echo "Opening tunnels through bastion ${BASTION_IP}:"
echo "  http://localhost:${LOCAL_MGMT_PORT}/management/coherence/cluster -> ${COHERENCE_NODE_IP}:30000"
echo "  http://localhost:${LOCAL_APP_PORT}/console -> app-bastion:8080"
echo
echo "Keep this terminal open while using the endpoints. Press Ctrl+C to close."

exec ssh -i "${SSH_KEY}" \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null \
  -N \
  -L "${LOCAL_MGMT_PORT}:${COHERENCE_NODE_IP}:30000" \
  -L "${LOCAL_APP_PORT}:127.0.0.1:8080" \
  "opc@${BASTION_IP}"
