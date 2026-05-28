# OCI Resource Manager Stack

This directory contains the self-contained Terraform package used by OCI
Resource Manager Stacks. It creates a complete POC environment:

- new VCN with public and private subnets, or reuse of existing VCN/subnets;
- app/bastion VM;
- Oracle Database 23ai Free VM;
- Redis VM;
- one Oracle Coherence storage VM;
- Java demo app built from this GitHub repository;
- seeded `COHDEMO.products` table;
- outputs for the SSH tunnel and browser URLs.

The defined tag variables are optional. Leave `defined_tag_namespace` empty for
tenancies that do not enforce defined tags, or fill the namespace and values
when the target tenancy has tag requirements.

`network_mode` defaults to `create_new`. Use `existing` only when the target
tenancy has restricted VCN quota or a mandatory network landing zone, then fill
`existing_vcn_ocid`, `existing_public_subnet_ocid` and
`existing_private_subnet_ocid`.

The Java VMs download Apache Maven 3.9.11 from the Apache Archive during
cloud-init because the Oracle Linux 8 package repository provides Maven 3.5.4,
which is too old for the demo build plugins.

The stack disables the guest OS `firewalld` service during cloud-init and uses
OCI NSGs/security lists as the network enforcement layer. In `existing` network
mode, review the existing subnet security lists before exposing the app-bastion
public IP.

The Stack package is generated with:

```bash
./scripts/package-orm-stack.sh
```

The generated file is:

```text
dist/poc-redis-coherence-orm.zip
```

After the Resource Manager apply job succeeds, wait for cloud-init to finish
inside the VMs. Then open the SSH tunnel shown in the Stack outputs and access:

```text
http://localhost:8081/console
http://localhost:8081/management-proxy/cluster
http://localhost:30000/management/coherence/cluster
```
