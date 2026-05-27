# Runbook de recriacao da POC

Este runbook descreve o caminho rapido para recriar a POC Redis x Oracle
Coherence no mesmo formato do ambiente atual: infraestrutura OCI, Oracle
Database Free, massa de produtos, aplicacao Java, Redis, Coherence storage node,
console web e tunnel SSH local.

## Pre-requisitos locais

- OCI CLI autenticado no profile usado pela POC.
- Terraform instalado.
- Docker instalado para build Maven em container.
- `jq`, `ssh` e `scp` instalados.
- VCN publica/privada ja existente na tenancy.
- Arquivo `infra/terraform/poc.auto.tfvars` preenchido a partir de
  `infra/terraform/terraform.tfvars.example`.
- Chave SSH local em `.ssh/poc_coherence_ed25519` e
  `.ssh/poc_coherence_ed25519.pub`.
- Senhas locais em:
  - `.secrets/db_admin_password.txt`
  - `.secrets/app_db_password.txt`

Os arquivos em `.ssh`, `.secrets`, `*.auto.tfvars`, state Terraform e artefatos
de build sao locais e nao entram no Git.

## Preflight

Antes de criar recursos, valide tenancy, regiao, VCN, tags obrigatorias e shape:

```bash
export OCI_TENANCY_NAME="<tenancy-name>"
export OCI_PARENT_COMPARTMENT_NAME="<parent-compartment-name>"
export OCI_VCN_NAME="<existing-vcn-name>"
./scripts/oci-preflight.sh
```

## Recriacao manual

1. Provisionar infraestrutura:

```bash
terraform -chdir=infra/terraform init
terraform -chdir=infra/terraform apply
```

2. Instalar/configurar Oracle Database Free e schema `COHDEMO`:

```bash
./scripts/setup-oracle-demo-db.sh
```

3. Criar massa de produtos para o robo:

```bash
PRODUCT_COUNT=50000 RESET_PRODUCTS=false ./scripts/seed-oracle-products.sh
```

4. Gerar o JAR da aplicacao:

```bash
docker run --rm -v "$PWD/app":/workspace -w /workspace \
  maven:3.9.11-eclipse-temurin-17 mvn -q -DskipTests package
```

5. Publicar app, Redis tuning e Coherence storage node:

```bash
./scripts/deploy-coherence-app.sh
```

6. Validar health dentro do app-bastion:

```bash
./scripts/ssh-bastion.sh 'curl -fsS http://127.0.0.1:8080/health'
```

7. Opcionalmente preparar cache para demo:

```bash
./scripts/ssh-bastion.sh \
  'curl -fsS -X POST "http://127.0.0.1:8080/cache/warm/redis?start=1&percent=100&total=50000&clear=true&resetStats=true"'

./scripts/ssh-bastion.sh \
  'curl -fsS -X POST "http://127.0.0.1:8080/cache/warm/coherence?start=1&percent=100&total=50000&clear=true&resetStats=true"'
```

8. Abrir tunnel local:

```bash
./scripts/tunnel-coherence-management.sh
```

Com o tunnel aberto:

```text
http://localhost:8081/console
http://localhost:8081/management-proxy/cluster
http://localhost:30000/management/coherence/cluster
```

## Recriacao assistida

O script abaixo executa a sequencia principal e, por padrao, abre o tunnel no
final:

```bash
./scripts/recreate-poc.sh
```

Opcoes uteis:

```bash
AUTO_APPROVE=true ./scripts/recreate-poc.sh
OPEN_TUNNEL=false ./scripts/recreate-poc.sh
RUN_SEED=false ./scripts/recreate-poc.sh
WARM_PERCENT=100 ./scripts/recreate-poc.sh
PRODUCT_COUNT=100000 ./scripts/recreate-poc.sh
```

`AUTO_APPROVE=true` adiciona `-auto-approve` ao `terraform apply`. Sem essa
variavel, o Terraform continua pedindo confirmacao interativa.

`OPEN_TUNNEL=true` faz o processo ficar preso no SSH tunnel no final. Para
terminar, use `Ctrl+C` no terminal do tunnel.

## Estado esperado da POC

Depois do deploy, a JVM da app inicia com `ACTIVE_CACHE_BACKEND=redis`, que e o
estado inicial da demonstracao. A troca para Coherence acontece pela console ou
por API:

```bash
curl -X POST http://localhost:8081/cache/backend/coherence
```

O conteudo de cache e estatisticas nao e preservado por Terraform. Para recriar
um estado de cache conhecido, use o warm-up de Redis e Coherence com o percentual
desejado.

## Observacoes operacionais

- O Terraform usa uma VCN e subnets existentes; ele nao cria a rede base.
- O Terraform state local permite destruir/reaplicar a mesma stack a partir desta
  pasta. Em um clone novo, restaure tambem o `terraform.tfstate` se a intencao
  for operar a stack existente. Sem state restaurado, um `terraform apply` tenta
  criar uma nova stack com os mesmos parametros.
- Mesmo em uma recriacao nova, copie/preencha `poc.auto.tfvars`, chaves e
  secrets antes de rodar os scripts.
- O install do Oracle Database Free baixa o RPM da Oracle durante o setup da VM.
- O build da app baixa dependencias Maven e usa a imagem
  `maven:3.9.11-eclipse-temurin-17`.
- O tunnel expõe a app localmente em `localhost:8081` e o management REST direto
  em `localhost:30000`.
