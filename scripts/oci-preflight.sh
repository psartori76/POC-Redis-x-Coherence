#!/usr/bin/env bash
set -euo pipefail

OCI_PROFILE="${OCI_PROFILE:-DEFAULT}"
OCI_REGION="${OCI_REGION:-us-ashburn-1}"
OCI_TENANCY_NAME="${OCI_TENANCY_NAME:-}"
OCI_PARENT_COMPARTMENT_NAME="${OCI_PARENT_COMPARTMENT_NAME:-${OCI_COMPARTMENT_NAME:-}}"
OCI_PARENT_COMPARTMENT_ID="${OCI_PARENT_COMPARTMENT_ID:-${OCI_COMPARTMENT_ID:-}}"
OCI_POC_COMPARTMENT_NAME="${OCI_POC_COMPARTMENT_NAME:-POC_Coherence}"
OCI_VCN_NAME="${OCI_VCN_NAME:-}"
OCI_SHAPE="${OCI_SHAPE:-VM.Standard.E4.Flex}"
TAG_NAMESPACE="${TAG_NAMESPACE:-0-ResourceControl}"

CONFIG_FILE="${OCI_CONFIG_FILE:-$HOME/.oci/config}"

REQUIRED_TAGS_JSON='{
  "0-ResourceControl": {
    "CreatedBy": "replace-with-created-by-value",
    "KeepResource": "POC Redis x Coherence",
    "CreatedAt": "2026-01-01T00:00:00Z",
    "ShutdownTime": "POC Redis x Coherence",
    "Team": "replace-with-team-value",
    "DeleteResource": "replace-with-delete-policy",
    "ShutdownResource": "replace-with-shutdown-policy"
  }
}'

log() {
  printf '\n==> %s\n' "$*"
}

ok() {
  printf 'OK  %s\n' "$*"
}

warn() {
  printf 'WARN %s\n' "$*" >&2
}

fail() {
  printf 'FAIL %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Comando nao encontrado: $1"
}

profile_value() {
  local key="$1"
  awk -F= -v profile="$OCI_PROFILE" -v key="$key" '
    BEGIN { found = 0 }
    /^\[/ { found = ($0 == "[" profile "]") }
    found && $1 ~ "^[[:space:]]*" key "[[:space:]]*$" {
      value = $2
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      print value
      exit
    }
  ' "$CONFIG_FILE"
}

json_len() {
  local payload
  payload="$(cat)"
  if [[ -z "$payload" ]]; then
    printf '0\n'
    return
  fi

  jq 'if type == "array" then length else (.data // []) | length end' <<<"$payload"
}

log "Ferramentas locais"
require_cmd oci
require_cmd jq
ok "oci $(oci --version)"
ok "jq $(jq --version)"

if command -v terraform >/dev/null 2>&1; then
  ok "$(terraform version | head -1)"
else
  warn "Terraform nao encontrado; necessario se escolhermos IaC com Terraform."
fi

if command -v java >/dev/null 2>&1; then
  ok "$(java -version 2>&1 | head -1)"
else
  warn "Java nao encontrado; necessario para a aplicacao/cluster Coherence."
fi

if command -v mvn >/dev/null 2>&1; then
  ok "$(mvn -version | head -1)"
else
  warn "Maven nao encontrado; podemos usar Maven Wrapper no projeto Java."
fi

if command -v docker >/dev/null 2>&1; then
  ok "$(docker --version)"
else
  warn "Docker nao encontrado; opcional para empacotar a demo."
fi

[[ -n "$OCI_TENANCY_NAME" ]] || fail "Defina OCI_TENANCY_NAME antes de rodar o preflight."
[[ -n "$OCI_PARENT_COMPARTMENT_ID" || -n "$OCI_PARENT_COMPARTMENT_NAME" ]] || fail "Defina OCI_PARENT_COMPARTMENT_ID ou OCI_PARENT_COMPARTMENT_NAME."
[[ -n "$OCI_VCN_NAME" ]] || fail "Defina OCI_VCN_NAME com o nome da VCN existente."

log "Profile OCI"
[[ -f "$CONFIG_FILE" ]] || fail "Arquivo de config nao encontrado: $CONFIG_FILE"
TENANCY_ID="$(profile_value tenancy)"
[[ -n "$TENANCY_ID" ]] || fail "Nao encontrei tenancy na profile $OCI_PROFILE"
ok "Profile $OCI_PROFILE encontrada em $CONFIG_FILE"

TENANCY_JSON="$(oci iam tenancy get \
  --profile "$OCI_PROFILE" \
  --region "$OCI_REGION" \
  --tenancy-id "$TENANCY_ID" \
  --output json)"

TENANCY_NAME="$(jq -r '.data.name' <<<"$TENANCY_JSON")"
HOME_REGION="$(jq -r '.data["home-region-key"]' <<<"$TENANCY_JSON")"
ok "Tenancy: $TENANCY_NAME; home region key: $HOME_REGION"

if [[ "$TENANCY_NAME" != "$OCI_TENANCY_NAME" ]]; then
  fail "Profile $OCI_PROFILE aponta para tenancy '$TENANCY_NAME', esperado '$OCI_TENANCY_NAME'"
fi

log "Regiao"
REGIONS_JSON="$(oci iam region-subscription list \
  --profile "$OCI_PROFILE" \
  --region "$OCI_REGION" \
  --output json)"

if jq -e --arg region "$OCI_REGION" '.data[] | select(."region-name" == $region)' <<<"$REGIONS_JSON" >/dev/null; then
  ok "Regiao $OCI_REGION esta subscribed na tenancy"
else
  fail "Regiao $OCI_REGION nao aparece como subscribed para a tenancy"
fi

ADS_JSON="$(oci iam availability-domain list \
  --profile "$OCI_PROFILE" \
  --region "$OCI_REGION" \
  --compartment-id "$TENANCY_ID" \
  --output json)"
ok "Availability domains: $(jq -r '[.data[].name] | join(", ")' <<<"$ADS_JSON")"

log "Compartimentos"
if [[ -z "$OCI_PARENT_COMPARTMENT_ID" ]]; then
  COMPARTMENTS_JSON="$(oci iam compartment list \
    --profile "$OCI_PROFILE" \
    --region "$OCI_REGION" \
    --compartment-id "$TENANCY_ID" \
    --compartment-id-in-subtree true \
    --access-level ACCESSIBLE \
    --lifecycle-state ACTIVE \
    --all \
    --output json)"

  MATCH_COUNT="$(jq --arg name "$OCI_PARENT_COMPARTMENT_NAME" '[.data[] | select(.name == $name)] | length' <<<"$COMPARTMENTS_JSON")"
  [[ "$MATCH_COUNT" == "1" ]] || fail "Esperava 1 compartimento chamado '$OCI_PARENT_COMPARTMENT_NAME', encontrei $MATCH_COUNT"
  OCI_PARENT_COMPARTMENT_ID="$(jq -r --arg name "$OCI_PARENT_COMPARTMENT_NAME" '.data[] | select(.name == $name) | .id' <<<"$COMPARTMENTS_JSON")"
fi
ok "Compartimento pai: $OCI_PARENT_COMPARTMENT_NAME"

POC_COMPARTMENTS_JSON="$(oci iam compartment list \
  --profile "$OCI_PROFILE" \
  --region "$OCI_REGION" \
  --compartment-id "$OCI_PARENT_COMPARTMENT_ID" \
  --access-level ACCESSIBLE \
  --lifecycle-state ACTIVE \
  --all \
  --output json)"

POC_MATCH_COUNT="$(jq --arg name "$OCI_POC_COMPARTMENT_NAME" '[.data[] | select(.name == $name)] | length' <<<"$POC_COMPARTMENTS_JSON")"
if [[ "$POC_MATCH_COUNT" == "1" ]]; then
  POC_COMPARTMENT_ID="$(jq -r --arg name "$OCI_POC_COMPARTMENT_NAME" '.data[] | select(.name == $name) | .id' <<<"$POC_COMPARTMENTS_JSON")"
  ok "Subcompartment POC existente: $OCI_POC_COMPARTMENT_NAME"
else
  POC_COMPARTMENT_ID=""
  warn "Subcompartment POC '$OCI_POC_COMPARTMENT_NAME' ainda nao existe abaixo de '$OCI_PARENT_COMPARTMENT_NAME'."
fi

log "Rede existente"
VCNS_JSON="$(oci network vcn list \
  --profile "$OCI_PROFILE" \
  --region "$OCI_REGION" \
  --compartment-id "$OCI_PARENT_COMPARTMENT_ID" \
  --all \
  --output json)"

VCN_MATCH_COUNT="$(jq --arg name "$OCI_VCN_NAME" '[.data[] | select(."display-name" == $name)] | length' <<<"$VCNS_JSON")"
[[ "$VCN_MATCH_COUNT" == "1" ]] || fail "Esperava 1 VCN chamada '$OCI_VCN_NAME' no compartimento pai, encontrei $VCN_MATCH_COUNT"
VCN_ID="$(jq -r --arg name "$OCI_VCN_NAME" '.data[] | select(."display-name" == $name) | .id' <<<"$VCNS_JSON")"
VCN_CIDR="$(jq -r --arg name "$OCI_VCN_NAME" '.data[] | select(."display-name" == $name) | ."cidr-block"' <<<"$VCNS_JSON")"
ok "VCN $OCI_VCN_NAME encontrada: $VCN_CIDR"

SUBNETS_JSON="$(oci network subnet list \
  --profile "$OCI_PROFILE" \
  --region "$OCI_REGION" \
  --compartment-id "$OCI_PARENT_COMPARTMENT_ID" \
  --vcn-id "$VCN_ID" \
  --all \
  --output json)"
ok "Subnets na VCN: $(json_len <<<"$SUBNETS_JSON")"
jq -r '.data[] | "  - " + ."display-name" + " / " + ."cidr-block" + " / " + (if ."prohibit-public-ip-on-vnic" then "private" else "public" end)' <<<"$SUBNETS_JSON"

log "Tags obrigatorias"
TAG_NS_JSON="$(oci iam tag-namespace list \
  --profile "$OCI_PROFILE" \
  --region "$OCI_REGION" \
  --compartment-id "$TENANCY_ID" \
  --all \
  --output json)"

TAG_NS_ID="$(jq -r --arg name "$TAG_NAMESPACE" '.data[] | select(.name == $name and ."lifecycle-state" == "ACTIVE") | .id' <<<"$TAG_NS_JSON" | head -1)"
[[ -n "$TAG_NS_ID" ]] || fail "Tag namespace '$TAG_NAMESPACE' nao encontrado ou nao esta ACTIVE"
ok "Tag namespace ativo: $TAG_NAMESPACE"

TAGS_JSON="$(oci iam tag list \
  --profile "$OCI_PROFILE" \
  --region "$OCI_REGION" \
  --tag-namespace-id "$TAG_NS_ID" \
  --all \
  --output json)"

for key in CreatedBy KeepResource CreatedAt ShutdownTime Team DeleteResource ShutdownResource; do
  if jq -e --arg key "$key" '.data[] | select(.name == $key and ."lifecycle-state" == "ACTIVE")' <<<"$TAGS_JSON" >/dev/null; then
    ok "Tag ativa: $TAG_NAMESPACE.$key"
  else
    fail "Tag obrigatoria nao encontrada ou inativa: $TAG_NAMESPACE.$key"
  fi
done
ok "Defined tags padrao: $(jq -c . <<<"$REQUIRED_TAGS_JSON")"

log "Recursos existentes no subcompartment POC"
if [[ -n "$POC_COMPARTMENT_ID" ]]; then
  RESOURCE_COMPARTMENT_ID="$POC_COMPARTMENT_ID"
else
  RESOURCE_COMPARTMENT_ID="$OCI_PARENT_COMPARTMENT_ID"
  warn "Como o subcompartment ainda nao existe, contando temporariamente recursos no compartimento pai."
fi

INSTANCES_JSON="$(oci compute instance list \
  --profile "$OCI_PROFILE" \
  --region "$OCI_REGION" \
  --compartment-id "$RESOURCE_COMPARTMENT_ID" \
  --all \
  --output json)"
ok "Compute instances: $(json_len <<<"$INSTANCES_JSON")"

ADB_JSON="$(oci db autonomous-database list \
  --profile "$OCI_PROFILE" \
  --region "$OCI_REGION" \
  --compartment-id "$RESOURCE_COMPARTMENT_ID" \
  --all \
  --output json)"
ok "Autonomous Databases: $(json_len <<<"$ADB_JSON")"

DBSYSTEM_JSON="$(oci db system list \
  --profile "$OCI_PROFILE" \
  --region "$OCI_REGION" \
  --compartment-id "$RESOURCE_COMPARTMENT_ID" \
  --all \
  --output json)"
ok "DB Systems: $(json_len <<<"$DBSYSTEM_JSON")"

log "Shape alvo"
AD_NAME="$(jq -r '.data[0].name' <<<"$ADS_JSON")"
SHAPES="$(oci compute shape list \
  --profile "$OCI_PROFILE" \
  --region "$OCI_REGION" \
  --compartment-id "$OCI_PARENT_COMPARTMENT_ID" \
  --availability-domain "$AD_NAME" \
  --all \
  --output json | jq -r --arg shape "$OCI_SHAPE" '.data[] | select(.shape == $shape) | .shape' | sort -u | jq -R -s 'split("\n") | map(select(length > 0)) | join(", ")' | jq -r '.')"

if [[ -n "$SHAPES" ]]; then
  ok "$OCI_SHAPE disponivel no AD $AD_NAME"
else
  fail "Nao encontrei o shape $OCI_SHAPE no AD $AD_NAME."
fi

log "Resultado"
ok "Preflight concluido. Nenhum recurso foi criado."
