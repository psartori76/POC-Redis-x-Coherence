resource "oci_core_instance" "app_bastion" {
  availability_domain = local.availability_domain
  compartment_id      = var.compartment_ocid
  display_name        = "${var.name_prefix}-app-bastion"
  shape               = var.shape
  defined_tags        = local.resource_defined_tags
  freeform_tags       = local.common_freeform_tags

  shape_config {
    ocpus         = var.app_bastion_ocpus
    memory_in_gbs = var.app_bastion_memory_gbs
  }

  create_vnic_details {
    assign_public_ip = true
    display_name     = "${var.name_prefix}-app-bastion-vnic"
    hostname_label   = "${local.hostname_prefix}app"
    nsg_ids          = [oci_core_network_security_group.app_bastion.id]
    subnet_id        = local.public_subnet_id
  }

  source_details {
    source_id   = data.oci_core_images.oracle_linux.images[0].id
    source_type = "image"
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data = base64encode(templatefile("${path.module}/cloud-init/app-bastion.yaml.tftpl", {
      db_private_ip        = oci_core_instance.db.private_ip
      redis_private_ip     = oci_core_instance.redis.private_ip
      coherence_private_ip = oci_core_instance.coherence.private_ip
      app_db_password_b64  = local.app_db_password_b64
      source_archive_b64   = local.source_archive_b64
      cache_ttl_seconds    = var.cache_ttl_seconds
      active_cache_backend = var.active_cache_backend
    }))
  }
}

resource "oci_core_instance" "db" {
  availability_domain = local.availability_domain
  compartment_id      = var.compartment_ocid
  display_name        = "${var.name_prefix}-db"
  shape               = var.shape
  defined_tags        = local.resource_defined_tags
  freeform_tags       = local.common_freeform_tags

  shape_config {
    ocpus         = var.db_ocpus
    memory_in_gbs = var.db_memory_gbs
  }

  create_vnic_details {
    assign_public_ip = false
    display_name     = "${var.name_prefix}-db-vnic"
    hostname_label   = "${local.hostname_prefix}db"
    nsg_ids          = [oci_core_network_security_group.db.id]
    subnet_id        = local.private_subnet_id
  }

  source_details {
    source_id   = data.oci_core_images.oracle_linux.images[0].id
    source_type = "image"
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data = base64encode(templatefile("${path.module}/cloud-init/oracle-db-23ai.yaml.tftpl", {
      db_admin_password_b64 = local.db_admin_password_b64
      app_db_password_b64   = local.app_db_password_b64
      product_count         = var.product_count
    }))
  }
}

resource "oci_core_instance" "redis" {
  availability_domain = local.availability_domain
  compartment_id      = var.compartment_ocid
  display_name        = "${var.name_prefix}-redis"
  shape               = var.shape
  defined_tags        = local.resource_defined_tags
  freeform_tags       = local.common_freeform_tags

  shape_config {
    ocpus         = var.redis_ocpus
    memory_in_gbs = var.redis_memory_gbs
  }

  create_vnic_details {
    assign_public_ip = false
    display_name     = "${var.name_prefix}-redis-vnic"
    hostname_label   = "${local.hostname_prefix}red"
    nsg_ids          = [oci_core_network_security_group.redis.id]
    subnet_id        = local.private_subnet_id
  }

  source_details {
    source_id   = data.oci_core_images.oracle_linux.images[0].id
    source_type = "image"
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data           = base64encode(templatefile("${path.module}/cloud-init/redis.yaml.tftpl", {}))
  }
}

resource "oci_core_instance" "coherence" {
  availability_domain = local.availability_domain
  compartment_id      = var.compartment_ocid
  display_name        = "${var.name_prefix}-coherence-01"
  shape               = var.shape
  defined_tags        = local.resource_defined_tags
  freeform_tags       = local.common_freeform_tags

  shape_config {
    ocpus         = var.coherence_ocpus
    memory_in_gbs = var.coherence_memory_gbs
  }

  create_vnic_details {
    assign_public_ip = false
    display_name     = "${var.name_prefix}-coherence-01-vnic"
    hostname_label   = "${local.hostname_prefix}coh"
    nsg_ids          = [oci_core_network_security_group.coherence.id]
    subnet_id        = local.private_subnet_id
  }

  source_details {
    source_id   = data.oci_core_images.oracle_linux.images[0].id
    source_type = "image"
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data = base64encode(templatefile("${path.module}/cloud-init/coherence-node.yaml.tftpl", {
      source_archive_b64 = local.source_archive_b64
    }))
  }
}
