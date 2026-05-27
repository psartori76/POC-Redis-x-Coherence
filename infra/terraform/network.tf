resource "oci_core_network_security_group" "app_bastion" {
  compartment_id = var.parent_compartment_ocid
  display_name   = "${var.name_prefix}-app-bastion-nsg"
  vcn_id         = var.vcn_ocid
  defined_tags   = local.defined_tags
}

resource "oci_core_network_security_group" "db" {
  compartment_id = var.parent_compartment_ocid
  display_name   = "${var.name_prefix}-db-nsg"
  vcn_id         = var.vcn_ocid
  defined_tags   = local.defined_tags
}

resource "oci_core_network_security_group" "coherence" {
  compartment_id = var.parent_compartment_ocid
  display_name   = "${var.name_prefix}-coherence-nsg"
  vcn_id         = var.vcn_ocid
  defined_tags   = local.defined_tags
}

resource "oci_core_network_security_group" "redis" {
  compartment_id = var.parent_compartment_ocid
  display_name   = "${var.name_prefix}-redis-nsg"
  vcn_id         = var.vcn_ocid
  defined_tags   = local.defined_tags
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

resource "oci_core_network_security_group_security_rule" "app_bastion_egress" {
  network_security_group_id = oci_core_network_security_group.app_bastion.id
  direction                 = "EGRESS"
  protocol                  = "all"
  destination               = "0.0.0.0/0"
  destination_type          = "CIDR_BLOCK"
  description               = "Outbound from app-bastion."
}

resource "oci_core_network_security_group_security_rule" "app_bastion_coherence_ingress" {
  network_security_group_id = oci_core_network_security_group.app_bastion.id
  direction                 = "INGRESS"
  protocol                  = "all"
  source                    = oci_core_network_security_group.coherence.id
  source_type               = "NETWORK_SECURITY_GROUP"
  description               = "Coherence cluster callbacks from storage node to app-bastion."
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

resource "oci_core_network_security_group_security_rule" "coherence_member_ingress" {
  network_security_group_id = oci_core_network_security_group.coherence.id
  direction                 = "INGRESS"
  protocol                  = "all"
  source                    = oci_core_network_security_group.coherence.id
  source_type               = "NETWORK_SECURITY_GROUP"
  description               = "Coherence node-to-node traffic."
}

resource "oci_core_network_security_group_security_rule" "coherence_cluster_from_app_bastion" {
  network_security_group_id = oci_core_network_security_group.coherence.id
  direction                 = "INGRESS"
  protocol                  = "all"
  source                    = oci_core_network_security_group.app_bastion.id
  source_type               = "NETWORK_SECURITY_GROUP"
  description               = "Coherence cluster traffic from app-bastion storage-disabled member."
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
