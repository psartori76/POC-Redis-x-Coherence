#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SSH_KEY="${SSH_KEY:-${PROJECT_ROOT}/.ssh/poc_coherence_ed25519}"
BASTION_IP="${BASTION_IP:-$(terraform -chdir="${PROJECT_ROOT}/infra/terraform" output -raw app_bastion_public_ip)}"

ssh -i "${SSH_KEY}" \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null \
  "opc@${BASTION_IP}" "$@"
