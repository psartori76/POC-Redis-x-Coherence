# Terraform - POC Coherence e Redis

Este Terraform prepara a infraestrutura da POC em OCI usando:

- OCI CLI profile `DEFAULT`;
- regiao `us-ashburn-1`;
- VCN e subnets existentes informadas em `poc.auto.tfvars`;
- subcompartment `POC_Coherence` para as instancias;
- shape `VM.Standard.E4.Flex`;
- Oracle Linux para as VMs;
- defined tags obrigatorias em `0-ResourceControl`.

## Recursos Criados

- 4 NSGs na VCN existente:
  - app-bastion;
  - Oracle DB;
  - Redis;
  - Coherence.
- 1 VM app-bastion na subnet publica.
- 1 VM Oracle Database 23ai Free na subnet privada.
- 1 VM Redis na subnet privada.
- 1 VM Coherence storage node na subnet privada.

## Uso

O arquivo `poc.auto.tfvars` e local, gerado para este ambiente e ignorado pelo Git.

```bash
terraform init
terraform plan
terraform apply
```

Depois do `apply`, use os scripts na raiz:

```bash
./scripts/setup-oracle-demo-db.sh
./scripts/deploy-coherence-app.sh
./scripts/tunnel-coherence-management.sh
```

## Acesso

A chave SSH local fica em:

```text
../../.ssh/poc_coherence_ed25519
```

As VMs privadas recebem a mesma chave publica e podem ser acessadas via
`ProxyJump` pelo app-bastion.
