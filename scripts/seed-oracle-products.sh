#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
TF_DIR="${PROJECT_ROOT}/infra/terraform"
SSH_KEY="${SSH_KEY:-${PROJECT_ROOT}/.ssh/poc_coherence_ed25519}"

PRODUCT_COUNT="${PRODUCT_COUNT:-50000}"
RESET_PRODUCTS="${RESET_PRODUCTS:-false}"

if ! [[ "$PRODUCT_COUNT" =~ ^[1-9][0-9]*$ ]]; then
  echo "PRODUCT_COUNT must be a positive integer. Current value: ${PRODUCT_COUNT}" >&2
  exit 1
fi

case "$RESET_PRODUCTS" in
  true | false) ;;
  *)
    echo "RESET_PRODUCTS must be true or false. Current value: ${RESET_PRODUCTS}" >&2
    exit 1
    ;;
esac

APP_PUBLIC_IP="${APP_PUBLIC_IP:-$(terraform -chdir="$TF_DIR" output -raw app_bastion_public_ip)}"
DB_PRIVATE_IP="${DB_PRIVATE_IP:-$(terraform -chdir="$TF_DIR" output -raw db_private_ip)}"

private_ssh_args=(
  -i "$SSH_KEY"
  -o "ProxyCommand=ssh -i '${SSH_KEY}' -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -W %h:%p opc@${APP_PUBLIC_IP}"
  -o StrictHostKeyChecking=no
  -o UserKnownHostsFile=/dev/null
)

echo "==> Seeding ${PRODUCT_COUNT} COHDEMO.products rows on ${DB_PRIVATE_IP}"
echo "==> RESET_PRODUCTS=${RESET_PRODUCTS}"

ssh "${private_ssh_args[@]}" "opc@${DB_PRIVATE_IP}" "sudo bash -s" <<REMOTE
set -euo pipefail
cat >/tmp/poc-coherence-products-seed.sql <<'SQL'
set define on
set verify off
set feedback on
set timing on
whenever sqlerror exit sql.sqlcode

define product_count = ${PRODUCT_COUNT}
define reset_products = ${RESET_PRODUCTS}

alter session set container = FREEPDB1;

declare
  table_count number;
begin
  select count(*) into table_count
  from all_tables
  where owner = 'COHDEMO'
    and table_name = 'PRODUCTS';

  if table_count = 0 then
    raise_application_error(-20000, 'COHDEMO.PRODUCTS does not exist. Run scripts/setup-oracle-demo-db.sh first.');
  end if;
end;
/

begin
  if lower('&&reset_products') = 'true' then
    execute immediate 'truncate table COHDEMO.products';
  end if;
end;
/

merge into COHDEMO.products target
using (
  select id,
         case
           when id = 1 then 'Seguro Auto'
           when id = 2 then 'Seguro Vida'
           when id = 3 then 'Conta Digital'
           else
             case mod(id, 12)
               when 0 then 'Seguro Auto Plus'
               when 1 then 'Seguro Vida Familiar'
               when 2 then 'Conta Digital Premium'
               when 3 then 'Cartao Platinum'
               when 4 then 'Seguro Residencial'
               when 5 then 'Emprestimo Pessoal'
               when 6 then 'Plano Odonto'
               when 7 then 'Conta PJ'
               when 8 then 'Previdencia Privada'
               when 9 then 'Investimento Renda Fixa'
               when 10 then 'Seguro Viagem'
               else 'Consorcio Auto'
             end || ' ' || to_char(id, 'FM000000')
         end name,
         cast(
           case
             when id = 1 then 89.90
             when id = 2 then 49.90
             when id = 3 then 0.00
             else round(9.90 + mod(id * 137, 25000) / 100, 2)
           end as number(12,2)
         ) price
  from (
    select level id
    from dual
    connect by level <= &&product_count
  )
) source
on (target.id = source.id)
when matched then update set
  target.name = source.name,
  target.price = source.price,
  target.updated_at = systimestamp
when not matched then insert (id, name, price, updated_at)
  values (source.id, source.name, source.price, systimestamp);

commit;

set linesize 220
column name format a36
select count(*) total_rows, min(id) min_id, max(id) max_id
from COHDEMO.products;

select id, name, price
from COHDEMO.products
where id in (1, 2, 3, &&product_count - 2, &&product_count - 1, &&product_count)
order by id;

exit
SQL

sudo su - oracle -c 'source /etc/profile.d/oracle-free-23ai.sh && sqlplus -s / as sysdba @/tmp/poc-coherence-products-seed.sql'
rm -f /tmp/poc-coherence-products-seed.sql
REMOTE

echo "==> Product seed complete"
