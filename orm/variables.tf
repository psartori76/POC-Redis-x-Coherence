variable "tenancy_ocid" {
  description = "Tenancy OCID. Resource Manager fills this automatically."
  type        = string
}

variable "region" {
  description = "OCI region where the POC will be deployed."
  type        = string
  default     = "us-ashburn-1"
}

variable "compartment_ocid" {
  description = "Compartment where all POC resources will be created."
  type        = string
}

variable "name_prefix" {
  description = "Display-name prefix for all resources."
  type        = string
  default     = "poc-rxc"

  validation {
    condition     = can(regex("^[a-zA-Z][a-zA-Z0-9-]{2,24}$", var.name_prefix))
    error_message = "Use 3-25 characters: letters, numbers and hyphen; start with a letter."
  }
}

variable "ssh_public_key" {
  description = "Public SSH key injected into all instances."
  type        = string
}

variable "allowed_ssh_cidr" {
  description = "CIDR allowed to SSH into the app-bastion."
  type        = string
  default     = "0.0.0.0/0"
}

variable "defined_tag_namespace" {
  description = "Optional OCI defined tag namespace. Leave empty when the tenancy does not require defined tags."
  type        = string
  default     = ""
}

variable "defined_tag_created_by" {
  description = "Optional value for the CreatedBy defined tag."
  type        = string
  default     = ""
}

variable "defined_tag_keep_resource" {
  description = "Optional value for the KeepResource defined tag."
  type        = string
  default     = ""
}

variable "defined_tag_created_at" {
  description = "Optional value for the CreatedAt defined tag."
  type        = string
  default     = ""
}

variable "defined_tag_shutdown_time" {
  description = "Optional value for the ShutdownTime defined tag."
  type        = string
  default     = ""
}

variable "defined_tag_team" {
  description = "Optional value for the Team defined tag."
  type        = string
  default     = ""
}

variable "defined_tag_delete_resource" {
  description = "Optional value for the DeleteResource defined tag."
  type        = string
  default     = ""
}

variable "defined_tag_shutdown_resource" {
  description = "Optional value for the ShutdownResource defined tag."
  type        = string
  default     = ""
}

variable "network_mode" {
  description = "Use create_new to create a VCN, or existing to reuse supplied VCN/subnet OCIDs."
  type        = string
  default     = "create_new"

  validation {
    condition     = contains(["create_new", "existing"], var.network_mode)
    error_message = "network_mode must be create_new or existing."
  }
}

variable "vcn_cidr" {
  description = "CIDR for the new POC VCN."
  type        = string
  default     = "10.42.0.0/16"
}

variable "public_subnet_cidr" {
  description = "CIDR for the public subnet containing the app-bastion."
  type        = string
  default     = "10.42.0.0/24"
}

variable "private_subnet_cidr" {
  description = "CIDR for the private subnet containing Oracle, Redis and Coherence."
  type        = string
  default     = "10.42.1.0/24"
}

variable "existing_vcn_ocid" {
  description = "Existing VCN OCID used when network_mode is existing."
  type        = string
  default     = ""
}

variable "existing_vcn_cidr" {
  description = "CIDR of the existing VCN used for internal security rules."
  type        = string
  default     = "10.0.0.0/16"
}

variable "existing_public_subnet_ocid" {
  description = "Existing public subnet OCID for the app-bastion when network_mode is existing."
  type        = string
  default     = ""
}

variable "existing_private_subnet_ocid" {
  description = "Existing private subnet OCID for Oracle, Redis and Coherence when network_mode is existing."
  type        = string
  default     = ""
}

variable "availability_domain_name" {
  description = "Availability domain name. Leave empty to use the first AD."
  type        = string
  default     = ""
}

variable "oracle_linux_version" {
  description = "Oracle Linux image version."
  type        = string
  default     = "8"
}

variable "shape" {
  description = "Compute shape for all POC VMs."
  type        = string
  default     = "VM.Standard.E4.Flex"
}

variable "app_bastion_ocpus" {
  type    = number
  default = 2
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

variable "redis_ocpus" {
  type    = number
  default = 2
}

variable "redis_memory_gbs" {
  type    = number
  default = 8
}

variable "coherence_ocpus" {
  type    = number
  default = 2
}

variable "coherence_memory_gbs" {
  type    = number
  default = 16
}

variable "db_admin_password" {
  description = "Oracle SYS/SYSTEM password for Oracle Database Free. Avoid quotes and whitespace."
  type        = string
  sensitive   = true
}

variable "app_db_password" {
  description = "Password for the COHDEMO application schema. Avoid quotes and whitespace."
  type        = string
  sensitive   = true
}

variable "product_count" {
  description = "Number of product rows seeded into COHDEMO.products."
  type        = number
  default     = 50000

  validation {
    condition     = var.product_count >= 3 && var.product_count <= 250000
    error_message = "product_count must be between 3 and 250000."
  }
}

variable "source_archive_url" {
  description = "Public ZIP archive containing this repository. The VMs download it and build the Java app."
  type        = string
  default     = "https://codeload.github.com/psartori76/POC-Redis-x-Coherence/zip/refs/heads/main"
}

variable "cache_ttl_seconds" {
  description = "Redis TTL used by the application."
  type        = number
  default     = 3600
}

variable "active_cache_backend" {
  description = "Initial active cache backend after app restart."
  type        = string
  default     = "redis"

  validation {
    condition     = contains(["redis", "coherence"], var.active_cache_backend)
    error_message = "active_cache_backend must be redis or coherence."
  }
}
