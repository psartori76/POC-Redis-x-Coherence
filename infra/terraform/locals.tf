locals {
  defined_tags = {
    for key, value in var.resource_control_tags :
    "${var.resource_control_tag_namespace}.${key}" => value
  }

  ssh_authorized_keys = file(var.ssh_public_key_path)
}
