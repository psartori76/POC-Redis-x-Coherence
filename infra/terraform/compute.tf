resource "oci_core_instance" "app_bastion" {
  availability_domain = var.availability_domain
  compartment_id      = var.poc_compartment_ocid
  display_name        = "${var.name_prefix}-app-bastion"
  shape               = var.shape
  defined_tags        = local.defined_tags

  lifecycle {
    ignore_changes = [metadata["user_data"]]
  }

  shape_config {
    ocpus         = var.app_bastion_ocpus
    memory_in_gbs = var.app_bastion_memory_gbs
  }

  create_vnic_details {
    assign_public_ip = true
    display_name     = "${var.name_prefix}-app-bastion-vnic"
    hostname_label   = "cohapp"
    nsg_ids          = [oci_core_network_security_group.app_bastion.id]
    subnet_id        = var.public_subnet_ocid
  }

  source_details {
    source_id   = data.oci_core_images.oracle_linux.images[0].id
    source_type = "image"
  }

  metadata = {
    ssh_authorized_keys = local.ssh_authorized_keys
    user_data           = base64encode(templatefile("${path.module}/cloud-init/app-bastion.yaml.tftpl", {}))
  }
}

resource "oci_core_instance" "db" {
  availability_domain = var.availability_domain
  compartment_id      = var.poc_compartment_ocid
  display_name        = "${var.name_prefix}-db"
  shape               = var.shape
  defined_tags        = local.defined_tags

  lifecycle {
    ignore_changes = [metadata["user_data"]]
  }

  shape_config {
    ocpus         = var.db_ocpus
    memory_in_gbs = var.db_memory_gbs
  }

  create_vnic_details {
    assign_public_ip = false
    display_name     = "${var.name_prefix}-db-vnic"
    hostname_label   = "cohdb"
    nsg_ids          = [oci_core_network_security_group.db.id]
    subnet_id        = var.private_subnet_ocid
  }

  source_details {
    source_id   = data.oci_core_images.oracle_linux.images[0].id
    source_type = "image"
  }

  metadata = {
    ssh_authorized_keys = local.ssh_authorized_keys
    user_data           = base64encode(templatefile("${path.module}/cloud-init/oracle-db-23ai.yaml.tftpl", {}))
  }
}

resource "oci_core_instance" "coherence" {
  count               = var.coherence_node_count
  availability_domain = var.availability_domain
  compartment_id      = var.poc_compartment_ocid
  display_name        = format("%s-coherence-%02d", var.name_prefix, count.index + 1)
  shape               = var.shape
  defined_tags        = local.defined_tags

  lifecycle {
    ignore_changes = [metadata["user_data"]]
  }

  shape_config {
    ocpus         = var.coherence_ocpus
    memory_in_gbs = var.coherence_memory_gbs
  }

  create_vnic_details {
    assign_public_ip = false
    display_name     = format("%s-coherence-%02d-vnic", var.name_prefix, count.index + 1)
    hostname_label   = format("cohnode%02d", count.index + 1)
    nsg_ids          = [oci_core_network_security_group.coherence.id]
    subnet_id        = var.private_subnet_ocid
  }

  source_details {
    source_id   = data.oci_core_images.oracle_linux.images[0].id
    source_type = "image"
  }

  metadata = {
    ssh_authorized_keys = local.ssh_authorized_keys
    user_data = base64encode(templatefile("${path.module}/cloud-init/coherence-node.yaml.tftpl", {
      node_index = count.index + 1
    }))
  }
}

resource "oci_core_instance" "redis" {
  availability_domain = var.availability_domain
  compartment_id      = var.poc_compartment_ocid
  display_name        = "${var.name_prefix}-redis"
  shape               = var.shape
  defined_tags        = local.defined_tags

  lifecycle {
    ignore_changes = [metadata["user_data"]]
  }

  shape_config {
    ocpus         = var.redis_ocpus
    memory_in_gbs = var.redis_memory_gbs
  }

  create_vnic_details {
    assign_public_ip = false
    display_name     = "${var.name_prefix}-redis-vnic"
    hostname_label   = "cohredis"
    nsg_ids          = [oci_core_network_security_group.redis.id]
    subnet_id        = var.private_subnet_ocid
  }

  source_details {
    source_id   = data.oci_core_images.oracle_linux.images[0].id
    source_type = "image"
  }

  metadata = {
    ssh_authorized_keys = local.ssh_authorized_keys
    user_data           = base64encode(templatefile("${path.module}/cloud-init/redis.yaml.tftpl", {}))
  }
}
