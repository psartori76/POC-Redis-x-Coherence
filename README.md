# POC Oracle Coherence e Redis on OCI

Este workspace organiza uma POC para demonstrar troca transparente entre Redis
e Oracle Coherence como camada de cache na frente de um Oracle Database em OCI.

## Objetivo

- manter o Oracle Database como fonte de verdade;
- executar Redis e Coherence lado a lado;
- expor uma aplicacao demo com `CacheProvider`;
- alternar o backend ativo em runtime pela console: Redis ou Coherence;
- demonstrar miss, hit, invalidacao, refresh e metricas sem mudar o contrato da aplicacao.

## Arquitetura Atual

Topologia reduzida para demo:

1. `poc-coherence-app-bastion`
   - subnet publica;
   - SSH de entrada;
   - aplicacao Java e console demo;
   - membro Coherence storage-disabled;
   - acessa Oracle, Redis e Coherence pela rede privada.
2. `poc-coherence-db`
   - subnet privada;
   - Oracle Database 23ai Free;
   - PDB `FREEPDB1`;
   - schema da demo `COHDEMO`.
3. `poc-coherence-redis`
   - subnet privada;
   - Redis na porta `6379`, acessivel apenas pelo app-bastion.
4. `poc-coherence-coherence-01`
   - subnet privada;
   - Coherence storage node;
   - Management over REST na porta `30000`, acessivel via app-bastion.

## Ambiente OCI

A POC foi preparada para usar uma VCN e subnets ja existentes. Os valores reais
do ambiente devem ficar em `infra/terraform/poc.auto.tfvars`, que e ignorado
pelo Git. Use `infra/terraform/terraform.tfvars.example` como modelo.

- OCI CLI profile padrao: `DEFAULT`.
- Regiao padrao: `us-ashburn-1`.
- Subcompartment POC sugerido: `POC_Coherence`.
- Shape padrao: `VM.Standard.E4.Flex`.
- Chave SSH local sugerida: `.ssh/poc_coherence_ed25519`.

## Tags Obrigatorias

Todos os recursos criados recebem defined tags no namespace `0-ResourceControl`:

| Tag | Valor |
| --- | --- |
| `CreatedBy` | definido em `resource_control_tags.CreatedBy` |
| `KeepResource` | definido em `resource_control_tags.KeepResource` |
| `CreatedAt` | definido em `resource_control_tags.CreatedAt` |
| `ShutdownTime` | definido em `resource_control_tags.ShutdownTime` |
| `Team` | definido em `resource_control_tags.Team` |
| `DeleteResource` | definido em `resource_control_tags.DeleteResource` |
| `ShutdownResource` | definido em `resource_control_tags.ShutdownResource` |

## Recriacao Rapida

Provisionar a infraestrutura:

```bash
terraform -chdir=infra/terraform apply
```

Configurar o Oracle Database e schema da demo:

```bash
./scripts/setup-oracle-demo-db.sh
```

Build local da aplicacao via Docker:

```bash
docker run --rm -v "$PWD/app":/workspace -w /workspace maven:3.9.11-eclipse-temurin-17 mvn -q -DskipTests package
```

Deploy da app e do Coherence storage node:

```bash
./scripts/deploy-coherence-app.sh
```

Abrir tuneis para uso local:

```bash
./scripts/tunnel-coherence-management.sh
```

Depois acesse:

```text
http://localhost:8081/console
http://localhost:30000/management/coherence/cluster
```

## Demo

Endpoints principais:

```text
GET    /health
GET    /metrics
GET    /cache/backend
POST   /cache/backend/redis
POST   /cache/backend/coherence
POST   /cache/backend/toggle
GET    /products/{id}
PUT    /products/{id}
DELETE /cache/products/{id}
POST   /cache/clear
POST   /cache/clear/redis
POST   /cache/clear/coherence
POST   /cache/warm/redis?start=1&percent=50&total=50000&clear=true&resetStats=true
POST   /cache/warm/coherence?start=1&percent=50&total=50000&clear=true&resetStats=true
POST   /metrics/reset/redis
POST   /metrics/reset/coherence
```

### Massa de dados

Para testes automatizados ou execucao por robo, carregue uma massa maior em
`COHDEMO.products`:

```bash
./scripts/seed-oracle-products.sh
```

Por padrao o script cria ou atualiza `50000` registros, mantendo os produtos
originais nos IDs `1`, `2` e `3`. Para escolher outro volume:

```bash
PRODUCT_COUNT=100000 ./scripts/seed-oracle-products.sh
```

Para recriar a massa do zero antes da carga:

```bash
RESET_PRODUCTS=true PRODUCT_COUNT=50000 ./scripts/seed-oracle-products.sh
```

Fluxo sugerido:

1. deixar Redis ativo;
2. iniciar o `Robo de consultas` na console;
3. observar a coluna Redis recebendo payloads, hits/misses e tempos de resposta;
4. clicar em `Intercambiar cache`;
5. observar que o robo continua chamando `GET /products/{id}` sem mudar nada;
6. acompanhar a coluna Coherence recebendo os novos payloads e seus proprios contadores;
7. comparar taxa de hit/miss, medias de tempo de hit, medias de tempo de miss
   e o indicador composto de Redis e Coherence lado a lado.

A console e dividida em tres colunas:

- `Robo de consultas`: dispara chamadas contra a aplicacao em intervalo configuravel, com modo sequencial ou aleatorio.
- `Redis`: exibe contadores, tempo de resposta e ultimo payload atendido pelo Redis.
- `Oracle Coherence`: exibe contadores, tempo de resposta e ultimo payload atendido pelo Coherence.

Cada coluna de cache tem botoes para zerar apenas aquele cache e zerar apenas
as estatisticas daquele backend. Tambem ha um campo `Warm %` em cada coluna,
aceitando de `0` a `100`, e um botao `Warm-up`. O warm-up carrega o percentual
escolhido da massa de `50000` produtos naquele cache, limpa o cache antes da
carga e zera as estatisticas ao final. Com `0%`, o cache fica limpo e as
estatisticas ficam zeradas; com `100%`, todos os produtos da massa sao
pre-carregados.

As metricas tambem ficam disponiveis por API em `GET /metrics`, no objeto
`backends.redis` e `backends.coherence`. Alem dos contadores, a API separa
medias de hit e miss, taxa de hit/miss, percentual de leituras que foram ao
Oracle, quantidade estimada de acessos ao banco evitados pelo cache e o score
composto da demo.

## Tuning de Performance

O ambiente foi ajustado para uma comparacao mais justa entre Redis e Coherence:

- app/bastion, Redis e Coherence usam 2 OCPUs por padrao;
- Redis roda como cache puro para a POC: `appendonly no`, `save ""`,
  `maxmemory 6gb`, `maxmemory-policy allkeys-lru`, `io-threads 2` e
  `tcp-keepalive 60`;
- o deploy aplica sysctl basico e desabilita Transparent Huge Pages nas VMs de
  cache/app;
- o Redis fica acessivel pela porta privada `6379/tcp`;
- o Coherence usa `7574` para discovery/NameService, `7575` para TCMP e
  `30000` para management REST;
- o Coherence usa POF para `Product`, com `pof-config.xml` e
  `ProductPofSerializer`;
- o cache `products` usa near-cache local na app, mantendo o back cache
  distribuido no storage node;
- o TTL da demo foi aumentado para 60 minutos, evitando expiracao durante
  apresentacoes e benchmarks manuais;
- o warm-up usa operacoes em lote: Redis via pipeline e Coherence via `putAll`.

Depois de deployar, um teste limpo pode ser preparado com:

```bash
curl -X POST "http://127.0.0.1:8080/cache/warm/redis?start=1&percent=100&total=50000&clear=true&resetStats=true"
curl -X POST "http://127.0.0.1:8080/cache/warm/coherence?start=1&percent=100&total=50000&clear=true&resetStats=true"
```

Como a porta publica da app pode estar bloqueada por rede/NSG, esses comandos
podem ser executados dentro do app-bastion ou via tunnel local.

## Preflight

Validacao read-only da tenancy e rede:

```bash
export OCI_TENANCY_NAME="<tenancy-name>"
export OCI_PARENT_COMPARTMENT_NAME="<parent-compartment-name>"
export OCI_VCN_NAME="<existing-vcn-name>"
./scripts/oci-preflight.sh
```

O script nao cria recursos; apenas valida acesso, regiao, VCN, tags e shape.
