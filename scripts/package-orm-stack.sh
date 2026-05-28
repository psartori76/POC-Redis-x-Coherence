#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ORM_DIR="${PROJECT_ROOT}/orm"
DIST_DIR="${PROJECT_ROOT}/dist"
ZIP_PATH="${DIST_DIR}/poc-redis-coherence-orm.zip"

command -v zip >/dev/null 2>&1 || {
  echo "Command not found: zip" >&2
  exit 1
}

mkdir -p "$DIST_DIR"
rm -f "$ZIP_PATH"

(
  cd "$ORM_DIR"
  zip -qr "$ZIP_PATH" . \
    -x '.terraform/*' \
    -x 'terraform.tfstate*' \
    -x '*.tfvars' \
    -x '*.auto.tfvars' \
    -x '.DS_Store'
)

echo "$ZIP_PATH"
