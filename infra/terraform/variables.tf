variable "profile" {
  description = "OCI CLI profile."
  type        = string
  default     = "DEFAULT"
}

variable "region" {
  description = "OCI region."
  type        = string
  default     = "us-ashburn-1"
}

variable "tenancy_ocid" {
  description = "Tenancy OCID."
  type        = string
}

variable "parent_compartment_ocid" {
  description = "Compartment that owns the existing VCN."
  type        = string
}

variable "poc_compartment_ocid" {
  description = "Subcompartment where POC compute resources are created."
  type        = string
}

variable "vcn_ocid" {
  description = "Existing ASH-VCN OCID."
  type        = string
}

variable "public_subnet_ocid" {
  description = "Existing public subnet OCID for bastion."
  type        = string
}

variable "private_subnet_ocid" {
  description = "Existing private subnet OCID for Oracle DB and Coherence."
  type        = string
}

variable "availability_domain" {
  description = "Availability domain for all POC VMs."
  type        = string
  default     = "IAfA:US-ASHBURN-AD-1"
}

variable "name_prefix" {
  description = "Resource display-name prefix."
  type        = string
  default     = "poc-coherence"
}

variable "shape" {
  description = "Compute shape for all POC VMs."
  type        = string
  default     = "VM.Standard.E4.Flex"
}

variable "oracle_linux_version" {
  description = "Oracle Linux image version."
  type        = string
  default     = "8"
}

variable "ssh_public_key_path" {
  description = "Public SSH key to inject into instances."
  type        = string
}

variable "allowed_ssh_cidr" {
  description = "CIDR allowed to SSH into the bastion."
  type        = string
  default     = "0.0.0.0/0"
}

variable "resource_control_tag_namespace" {
  description = "OCI defined tag namespace used by the target tenancy."
  type        = string
  default     = "0-ResourceControl"
}

variable "resource_control_tags" {
  description = "Defined tag values required by the target tenancy."
  type        = map(string)
  default = {
    CreatedBy        = "replace-with-created-by-value"
    KeepResource     = "POC Redis x Coherence"
    CreatedAt        = "replace-with-created-at-value"
    ShutdownTime     = "POC Redis x Coherence"
    Team             = "replace-with-team-value"
    DeleteResource   = "replace-with-delete-policy"
    ShutdownResource = "replace-with-shutdown-policy"
  }
}

variable "app_bastion_ocpus" {
  type    = number
  default = 1
}

variable "app_bastion_memory_gbs" {
  type    = number
  default = 16
}

variable "db_ocpus" {
  type    = number
  default = 2
}

variable "db_memory_gbs" {
  type    = number
  default = 32
}

variable "coherence_node_count" {
  type    = number
  default = 1
}

variable "coherence_ocpus" {
  type    = number
  default = 1
}

variable "coherence_memory_gbs" {
  type    = number
  default = 16
}

variable "redis_ocpus" {
  type    = number
  default = 1
}

variable "redis_memory_gbs" {
  type    = number
  default = 8
}
