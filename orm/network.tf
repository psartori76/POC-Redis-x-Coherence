resource "oci_core_vcn" "poc" {
  count = local.create_network ? 1 : 0

  compartment_id = var.compartment_ocid
  cidr_block     = var.vcn_cidr
  display_name   = "${var.name_prefix}-vcn"
  dns_label      = "rxcpoc"
  defined_tags   = local.resource_defined_tags
  freeform_tags  = local.common_freeform_tags
}

resource "oci_core_internet_gateway" "poc" {
  count = local.create_network ? 1 : 0

  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.poc[0].id
  display_name   = "${var.name_prefix}-igw"
  enabled        = true
  defined_tags   = local.resource_defined_tags
  freeform_tags  = local.common_freeform_tags
}

resource "oci_core_nat_gateway" "poc" {
  count = local.create_network ? 1 : 0

  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.poc[0].id
  display_name   = "${var.name_prefix}-nat"
  defined_tags   = local.resource_defined_tags
  freeform_tags  = local.common_freeform_tags
}

resource "oci_core_route_table" "public" {
  count = local.create_network ? 1 : 0

  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.poc[0].id
  display_name   = "${var.name_prefix}-public-rt"
  defined_tags   = local.resource_defined_tags
  freeform_tags  = local.common_freeform_tags

  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.poc[0].id
  }
}

resource "oci_core_route_table" "private" {
  count = local.create_network ? 1 : 0

  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.poc[0].id
  display_name   = "${var.name_prefix}-private-rt"
  defined_tags   = local.resource_defined_tags
  freeform_tags  = local.common_freeform_tags

  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_nat_gateway.poc[0].id
  }
}

resource "oci_core_security_list" "public" {
  count = local.create_network ? 1 : 0

  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.poc[0].id
  display_name   = "${var.name_prefix}-public-sl"
  defined_tags   = local.resource_defined_tags
  freeform_tags  = local.common_freeform_tags

  ingress_security_rules {
    protocol = "6"
    source   = var.allowed_ssh_cidr

    tcp_options {
      min = 22
      max = 22
    }
  }

  ingress_security_rules {
    protocol = "all"
    source   = local.effective_vcn_cidr
  }

  egress_security_rules {
    protocol    = "all"
    destination = "0.0.0.0/0"
  }
}

resource "oci_core_security_list" "private" {
  count = local.create_network ? 1 : 0

  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.poc[0].id
  display_name   = "${var.name_prefix}-private-sl"
  defined_tags   = local.resource_defined_tags
  freeform_tags  = local.common_freeform_tags

  ingress_security_rules {
    protocol = "all"
    source   = local.effective_vcn_cidr
  }

  egress_security_rules {
    protocol    = "all"
    destination = "0.0.0.0/0"
  }
}

resource "oci_core_subnet" "public" {
  count = local.create_network ? 1 : 0

  compartment_id             = var.compartment_ocid
  vcn_id                     = oci_core_vcn.poc[0].id
  cidr_block                 = var.public_subnet_cidr
  display_name               = "${var.name_prefix}-public-subnet"
  dns_label                  = "rxcpub"
  route_table_id             = oci_core_route_table.public[0].id
  security_list_ids          = [oci_core_security_list.public[0].id]
  prohibit_public_ip_on_vnic = false
  defined_tags               = local.resource_defined_tags
  freeform_tags              = local.common_freeform_tags
}

resource "oci_core_subnet" "private" {
  count = local.create_network ? 1 : 0

  compartment_id             = var.compartment_ocid
  vcn_id                     = oci_core_vcn.poc[0].id
  cidr_block                 = var.private_subnet_cidr
  display_name               = "${var.name_prefix}-private-subnet"
  dns_label                  = "rxcpriv"
  route_table_id             = oci_core_route_table.private[0].id
  security_list_ids          = [oci_core_security_list.private[0].id]
  prohibit_public_ip_on_vnic = true
  defined_tags               = local.resource_defined_tags
  freeform_tags              = local.common_freeform_tags
}

resource "oci_core_network_security_group" "app_bastion" {
  compartment_id = var.compartment_ocid
  display_name   = "${var.name_prefix}-app-bastion-nsg"
  vcn_id         = local.effective_vcn_id
  defined_tags   = local.resource_defined_tags
  freeform_tags  = local.common_freeform_tags
}

resource "oci_core_network_security_group" "db" {
  compartment_id = var.compartment_ocid
  display_name   = "${var.name_prefix}-db-nsg"
  vcn_id         = local.effective_vcn_id
  defined_tags   = local.resource_defined_tags
  freeform_tags  = local.common_freeform_tags
}

resource "oci_core_network_security_group" "coherence" {
  compartment_id = var.compartment_ocid
  display_name   = "${var.name_prefix}-coherence-nsg"
  vcn_id         = local.effective_vcn_id
  defined_tags   = local.resource_defined_tags
  freeform_tags  = local.common_freeform_tags
}

resource "oci_core_network_security_group" "redis" {
  compartment_id = var.compartment_ocid
  display_name   = "${var.name_prefix}-redis-nsg"
  vcn_id         = local.effective_vcn_id
  defined_tags   = local.resource_defined_tags
  freeform_tags  = local.common_freeform_tags
}

resource "oci_core_network_security_group_security_rule" "app_bastion_ssh_ingress" {
  network_security_group_id = oci_core_network_security_group.app_bastion.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = var.allowed_ssh_cidr
  source_type               = "CIDR_BLOCK"
  description               = "SSH to app-bastion."

  tcp_options {
    destination_port_range {
      min = 22
      max = 22
    }
  }
}

resource "oci_core_network_security_group_security_rule" "app_bastion_coherence_ingress" {
  network_security_group_id = oci_core_network_security_group.app_bastion.id
  direction                 = "INGRESS"
  protocol                  = "all"
  source                    = oci_core_network_security_group.coherence.id
  source_type               = "NETWORK_SECURITY_GROUP"
  description               = "Coherence cluster callbacks from storage node to app-bastion."
}

resource "oci_core_network_security_group_security_rule" "app_bastion_egress" {
  network_security_group_id = oci_core_network_security_group.app_bastion.id
  direction                 = "EGRESS"
  protocol                  = "all"
  destination               = "0.0.0.0/0"
  destination_type          = "CIDR_BLOCK"
  description               = "Outbound from app-bastion."
}

resource "oci_core_network_security_group_security_rule" "db_ssh_from_app_bastion" {
  network_security_group_id = oci_core_network_security_group.db.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = oci_core_network_security_group.app_bastion.id
  source_type               = "NETWORK_SECURITY_GROUP"
  description               = "SSH from app-bastion to DB VM."

  tcp_options {
    destination_port_range {
      min = 22
      max = 22
    }
  }
}

resource "oci_core_network_security_group_security_rule" "db_sqlnet_from_app_bastion" {
  network_security_group_id = oci_core_network_security_group.db.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = oci_core_network_security_group.app_bastion.id
  source_type               = "NETWORK_SECURITY_GROUP"
  description               = "SQL*Net from app-bastion to Oracle DB."

  tcp_options {
    destination_port_range {
      min = 1521
      max = 1521
    }
  }
}

resource "oci_core_network_security_group_security_rule" "db_egress" {
  network_security_group_id = oci_core_network_security_group.db.id
  direction                 = "EGRESS"
  protocol                  = "all"
  destination               = "0.0.0.0/0"
  destination_type          = "CIDR_BLOCK"
  description               = "Outbound from DB VM for package install and updates."
}

resource "oci_core_network_security_group_security_rule" "coherence_ssh_from_app_bastion" {
  network_security_group_id = oci_core_network_security_group.coherence.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = oci_core_network_security_group.app_bastion.id
  source_type               = "NETWORK_SECURITY_GROUP"
  description               = "SSH from app-bastion to Coherence node."

  tcp_options {
    destination_port_range {
      min = 22
      max = 22
    }
  }
}

resource "oci_core_network_security_group_security_rule" "coherence_cluster_from_app_bastion" {
  network_security_group_id = oci_core_network_security_group.coherence.id
  direction                 = "INGRESS"
  protocol                  = "all"
  source                    = oci_core_network_security_group.app_bastion.id
  source_type               = "NETWORK_SECURITY_GROUP"
  description               = "Coherence cluster traffic from app-bastion storage-disabled member."
}

resource "oci_core_network_security_group_security_rule" "coherence_member_ingress" {
  network_security_group_id = oci_core_network_security_group.coherence.id
  direction                 = "INGRESS"
  protocol                  = "all"
  source                    = oci_core_network_security_group.coherence.id
  source_type               = "NETWORK_SECURITY_GROUP"
  description               = "Coherence node-to-node traffic."
}

resource "oci_core_network_security_group_security_rule" "coherence_management_http_from_app_bastion" {
  network_security_group_id = oci_core_network_security_group.coherence.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = oci_core_network_security_group.app_bastion.id
  source_type               = "NETWORK_SECURITY_GROUP"
  description               = "Coherence Management over REST from app-bastion."

  tcp_options {
    destination_port_range {
      min = 30000
      max = 30000
    }
  }
}

resource "oci_core_network_security_group_security_rule" "coherence_egress" {
  network_security_group_id = oci_core_network_security_group.coherence.id
  direction                 = "EGRESS"
  protocol                  = "all"
  destination               = "0.0.0.0/0"
  destination_type          = "CIDR_BLOCK"
  description               = "Outbound from Coherence nodes."
}

resource "oci_core_network_security_group_security_rule" "redis_ssh_from_app_bastion" {
  network_security_group_id = oci_core_network_security_group.redis.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = oci_core_network_security_group.app_bastion.id
  source_type               = "NETWORK_SECURITY_GROUP"
  description               = "SSH from app-bastion to Redis VM."

  tcp_options {
    destination_port_range {
      min = 22
      max = 22
    }
  }
}

resource "oci_core_network_security_group_security_rule" "redis_from_app_bastion" {
  network_security_group_id = oci_core_network_security_group.redis.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = oci_core_network_security_group.app_bastion.id
  source_type               = "NETWORK_SECURITY_GROUP"
  description               = "Redis traffic from app-bastion."

  tcp_options {
    destination_port_range {
      min = 6379
      max = 6379
    }
  }
}

resource "oci_core_network_security_group_security_rule" "redis_egress" {
  network_security_group_id = oci_core_network_security_group.redis.id
  direction                 = "EGRESS"
  protocol                  = "all"
  destination               = "0.0.0.0/0"
  destination_type          = "CIDR_BLOCK"
  description               = "Outbound from Redis VM for package install and updates."
}
