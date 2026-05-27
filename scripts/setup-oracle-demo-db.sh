#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
TF_DIR="${PROJECT_ROOT}/infra/terraform"
SSH_KEY="${SSH_KEY:-${PROJECT_ROOT}/.ssh/poc_coherence_ed25519}"

DB_ADMIN_PASSWORD_FILE="${PROJECT_ROOT}/.secrets/db_admin_password.txt"
APP_DB_PASSWORD_FILE="${PROJECT_ROOT}/.secrets/app_db_password.txt"

[[ -f "$DB_ADMIN_PASSWORD_FILE" ]] || {
  echo "DB admin password file not found: $DB_ADMIN_PASSWORD_FILE" >&2
  exit 1
}

[[ -f "$APP_DB_PASSWORD_FILE" ]] || {
  echo "App DB password file not found: $APP_DB_PASSWORD_FILE" >&2
  exit 1
}

APP_PUBLIC_IP="${APP_PUBLIC_IP:-$(terraform -chdir="$TF_DIR" output -raw app_bastion_public_ip)}"
DB_PRIVATE_IP="${DB_PRIVATE_IP:-$(terraform -chdir="$TF_DIR" output -raw db_private_ip)}"
DB_ADMIN_PASSWORD="$(cat "$DB_ADMIN_PASSWORD_FILE")"
APP_DB_PASSWORD="$(cat "$APP_DB_PASSWORD_FILE")"
APP_DB_PASSWORD_SQL="${APP_DB_PASSWORD//\"/\"\"}"

private_ssh_args=(
  -i "$SSH_KEY"
  -o "ProxyCommand=ssh -i '${SSH_KEY}' -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -W %h:%p opc@${APP_PUBLIC_IP}"
  -o StrictHostKeyChecking=no
  -o UserKnownHostsFile=/dev/null
)

echo "==> Installing/configuring Oracle Database 23ai Free on ${DB_PRIVATE_IP}"
ssh "${private_ssh_args[@]}" "opc@${DB_PRIVATE_IP}" "sudo /opt/poc/install-oracle-db-free-23ai.sh '${DB_ADMIN_PASSWORD}'"

echo "==> Creating COHDEMO schema and demo products"
ssh "${private_ssh_args[@]}" "opc@${DB_PRIVATE_IP}" "sudo bash -s" <<REMOTE
set -euo pipefail
cat >/tmp/poc-coherence-demo.sql <<'SQL'
set define off
whenever sqlerror exit sql.sqlcode

alter session set container = FREEPDB1;

declare
  user_count number;
begin
  select count(*) into user_count from dba_users where username = 'COHDEMO';
  if user_count = 0 then
    execute immediate 'create user COHDEMO identified by "${APP_DB_PASSWORD_SQL}" quota unlimited on users';
  else
    execute immediate 'alter user COHDEMO identified by "${APP_DB_PASSWORD_SQL}" account unlock';
  end if;
end;
/

grant create session, create table to COHDEMO;

declare
  table_count number;
begin
  select count(*) into table_count
  from all_tables
  where owner = 'COHDEMO'
    and table_name = 'PRODUCTS';

  if table_count = 0 then
    execute immediate '
      create table COHDEMO.products (
        id number primary key,
        name varchar2(200) not null,
        price number(12,2) not null,
        updated_at timestamp default systimestamp not null
      )';
  end if;
end;
/

merge into COHDEMO.products target
using (
  select 1 id, 'Seguro Auto' name, 89.90 price from dual union all
  select 2 id, 'Seguro Vida' name, 49.90 price from dual union all
  select 3 id, 'Conta Digital' name, 0.00 price from dual
) source
on (target.id = source.id)
when matched then update set
  target.name = source.name,
  target.price = source.price,
  target.updated_at = systimestamp
when not matched then insert (id, name, price, updated_at)
  values (source.id, source.name, source.price, systimestamp);

commit;
exit
SQL

sudo su - oracle -c 'source /etc/profile.d/oracle-free-23ai.sh && sqlplus -s / as sysdba @/tmp/poc-coherence-demo.sql'
rm -f /tmp/poc-coherence-demo.sql
REMOTE

echo "==> Oracle demo schema ready"
