data "oci_identity_availability_domains" "ads" {
  compartment_id = var.tenancy_ocid
}

data "oci_core_images" "oracle_linux" {
  compartment_id           = var.compartment_ocid
  operating_system         = "Oracle Linux"
  operating_system_version = var.oracle_linux_version
  shape                    = var.shape
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

locals {
  create_network      = var.network_mode == "create_new"
  effective_vcn_id    = local.create_network ? oci_core_vcn.poc[0].id : var.existing_vcn_ocid
  effective_vcn_cidr  = local.create_network ? var.vcn_cidr : var.existing_vcn_cidr
  public_subnet_id    = local.create_network ? oci_core_subnet.public[0].id : var.existing_public_subnet_ocid
  private_subnet_id   = local.create_network ? oci_core_subnet.private[0].id : var.existing_private_subnet_ocid
  hostname_prefix     = substr(replace(lower(var.name_prefix), "/[^a-z0-9]/", ""), 0, 11)
  availability_domain = var.availability_domain_name != "" ? var.availability_domain_name : data.oci_identity_availability_domains.ads.availability_domains[0].name
  common_freeform_tags = {
    App       = "POC Redis x Oracle Coherence"
    ManagedBy = "OCI Resource Manager"
  }
  resource_defined_tags = trimspace(var.defined_tag_namespace) == "" ? {} : {
    "${var.defined_tag_namespace}.CreatedBy"        = var.defined_tag_created_by
    "${var.defined_tag_namespace}.KeepResource"     = var.defined_tag_keep_resource
    "${var.defined_tag_namespace}.CreatedAt"        = var.defined_tag_created_at
    "${var.defined_tag_namespace}.ShutdownTime"     = var.defined_tag_shutdown_time
    "${var.defined_tag_namespace}.Team"             = var.defined_tag_team
    "${var.defined_tag_namespace}.DeleteResource"   = var.defined_tag_delete_resource
    "${var.defined_tag_namespace}.ShutdownResource" = var.defined_tag_shutdown_resource
  }

  db_admin_password_b64 = base64encode(var.db_admin_password)
  app_db_password_b64   = base64encode(var.app_db_password)
  source_archive_b64    = base64encode(var.source_archive_url)
}
