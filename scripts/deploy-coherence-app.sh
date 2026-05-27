#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
TF_DIR="${PROJECT_ROOT}/infra/terraform"

JAR_PATH="${PROJECT_ROOT}/app/target/coherence-cache-demo-1.0.0.jar"
DB_PASSWORD_FILE="${PROJECT_ROOT}/.secrets/app_db_password.txt"
SSH_KEY="${SSH_KEY:-${PROJECT_ROOT}/.ssh/poc_coherence_ed25519}"

[[ -f "$JAR_PATH" ]] || {
  echo "Jar not found: $JAR_PATH" >&2
  exit 1
}

[[ -f "$DB_PASSWORD_FILE" ]] || {
  echo "DB password file not found: $DB_PASSWORD_FILE" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Command not found: $1" >&2
    exit 1
  }
}

tf_output() {
  terraform -chdir="$TF_DIR" output -raw "$1"
}

require_cmd terraform
require_cmd jq

APP_PUBLIC_IP="${APP_PUBLIC_IP:-$(tf_output app_bastion_public_ip)}"
APP_PRIVATE_IP="${APP_PRIVATE_IP:-$(tf_output app_bastion_private_ip)}"
DB_PRIVATE_IP="${DB_PRIVATE_IP:-$(tf_output db_private_ip)}"
REDIS_PRIVATE_IP="${REDIS_PRIVATE_IP:-$(tf_output redis_private_ip)}"
COHERENCE_PRIVATE_IP="${COHERENCE_PRIVATE_IP:-$(terraform -chdir="$TF_DIR" output -json coherence_private_ips | jq -r '.[0]')}"
DB_PASSWORD="$(cat "$DB_PASSWORD_FILE")"

app_ssh_args=(
  -i "$SSH_KEY"
  -o StrictHostKeyChecking=no
  -o UserKnownHostsFile=/dev/null
)

private_ssh_args=(
  -i "$SSH_KEY"
  -o "ProxyCommand=ssh -i '${SSH_KEY}' -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -W %h:%p opc@${APP_PUBLIC_IP}"
  -o StrictHostKeyChecking=no
  -o UserKnownHostsFile=/dev/null
)

echo "==> Deploying app to app-bastion ${APP_PUBLIC_IP}"
scp "${app_ssh_args[@]}" "$JAR_PATH" "opc@${APP_PUBLIC_IP}:/tmp/coherence-cache-demo.jar"
ssh "${app_ssh_args[@]}" "opc@${APP_PUBLIC_IP}" "bash -s" <<REMOTE
set -euo pipefail
sudo mkdir -p /opt/poc/app
sudo mv /tmp/coherence-cache-demo.jar /opt/poc/app/coherence-cache-demo.jar
sudo chown -R opc:opc /opt/poc/app
if systemctl is-active --quiet firewalld; then
  sudo timeout 20s firewall-cmd --permanent --add-port=8080/tcp || true
  sudo timeout 20s firewall-cmd --permanent --add-port=7575/tcp || true
  sudo timeout 20s firewall-cmd --permanent --add-port=7575/udp || true
  sudo timeout 20s firewall-cmd --reload || true
fi
sudo tee /etc/poc-cache-demo.env >/dev/null <<ENV
APP_MODE=app
NODE_IP=${APP_PRIVATE_IP}
NODE_NAME=app-bastion
HTTP_PORT=8080
DB_URL=jdbc:oracle:thin:@//${DB_PRIVATE_IP}:1521/FREEPDB1
DB_USER=COHDEMO
DB_PASSWORD=${DB_PASSWORD}
REDIS_URL=redis://${REDIS_PRIVATE_IP}:6379
ACTIVE_CACHE_BACKEND=redis
CACHE_TTL_SECONDS=300
COHERENCE_WKA=${COHERENCE_PRIVATE_IP}
COHERENCE_MANAGEMENT_URL=http://${COHERENCE_PRIVATE_IP}:30000/management/coherence
ENV
sudo chmod 600 /etc/poc-cache-demo.env
sudo tee /etc/systemd/system/poc-cache-demo.service >/dev/null <<'SERVICE'
[Unit]
Description=POC Redis/Coherence Cache Demo App
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=opc
EnvironmentFile=/etc/poc-cache-demo.env
WorkingDirectory=/opt/poc/app
ExecStart=/bin/bash -lc 'exec /usr/bin/java -Dcoherence.cluster=poc-coherence -Dcoherence.role=cache-client -Dcoherence.localhost="\$NODE_IP" -Dcoherence.wka="\$COHERENCE_WKA" -Dcoherence.localport=7575 -Dcoherence.distributed.localstorage=false -jar /opt/poc/app/coherence-cache-demo.jar'
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
SERVICE
sudo systemctl daemon-reload
sudo systemctl enable poc-cache-demo.service
sudo systemctl restart poc-cache-demo.service
sudo systemctl --no-pager --full status poc-cache-demo.service | sed -n '1,14p' || true
REMOTE

echo "==> Deploying Coherence storage node to ${COHERENCE_PRIVATE_IP}"
scp "${private_ssh_args[@]}" "$JAR_PATH" "opc@${COHERENCE_PRIVATE_IP}:/tmp/coherence-cache-demo.jar"
ssh "${private_ssh_args[@]}" "opc@${COHERENCE_PRIVATE_IP}" "bash -s" <<REMOTE
set -euo pipefail
sudo mkdir -p /opt/poc/coherence
sudo mv /tmp/coherence-cache-demo.jar /opt/poc/coherence/coherence-cache-demo.jar
sudo chown -R opc:opc /opt/poc/coherence
if systemctl is-active --quiet firewalld; then
  sudo timeout 20s firewall-cmd --permanent --add-port=7/tcp || true
  sudo timeout 20s firewall-cmd --permanent --add-port=7575/tcp || true
  sudo timeout 20s firewall-cmd --permanent --add-port=7575/udp || true
  sudo timeout 20s firewall-cmd --permanent --add-port=30000/tcp || true
  sudo timeout 20s firewall-cmd --reload || true
fi
sudo tee /etc/poc-coherence-node.env >/dev/null <<ENV
APP_MODE=coherence-node
NODE_IP=${COHERENCE_PRIVATE_IP}
NODE_NAME=coherence-storage-01
COHERENCE_WKA=${COHERENCE_PRIVATE_IP}
ENV
sudo tee /etc/systemd/system/poc-coherence-node.service >/dev/null <<'SERVICE'
[Unit]
Description=POC Coherence Storage Node
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=opc
EnvironmentFile=/etc/poc-coherence-node.env
WorkingDirectory=/opt/poc/coherence
ExecStart=/bin/bash -lc 'exec /usr/bin/java -Dcoherence.cluster=poc-coherence -Dcoherence.role=cache-storage -Dcoherence.localhost="\$NODE_IP" -Dcoherence.wka="\$COHERENCE_WKA" -Dcoherence.localport=7575 -Dcoherence.management=all -Dcoherence.management.remote=true -Dcoherence.management.http=all -Dcoherence.management.http.address=0.0.0.0 -Dcoherence.management.http.port=30000 -jar /opt/poc/coherence/coherence-cache-demo.jar'
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
SERVICE
sudo systemctl daemon-reload
sudo systemctl enable poc-coherence-node.service
sudo systemctl restart poc-coherence-node.service
sudo systemctl --no-pager --full status poc-coherence-node.service | sed -n '1,14p'
REMOTE

echo "==> Restarting app after Coherence storage node deployment"
ssh "${app_ssh_args[@]}" "opc@${APP_PUBLIC_IP}" "sudo systemctl restart poc-cache-demo.service && sudo systemctl --no-pager --full status poc-cache-demo.service | sed -n '1,14p'"
