#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SSH_KEY="${SSH_KEY:-${PROJECT_ROOT}/.ssh/poc_coherence_ed25519}"
BASTION_IP="${BASTION_IP:-$(terraform -chdir="${PROJECT_ROOT}/infra/terraform" output -raw app_bastion_public_ip)}"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <private-ip> [command...]" >&2
  exit 2
fi

PRIVATE_IP="$1"
shift

ssh -i "${SSH_KEY}" \
  -o "ProxyCommand=ssh -i '${SSH_KEY}' -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -W %h:%p opc@${BASTION_IP}" \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null \
  "opc@${PRIVATE_IP}" "$@"
