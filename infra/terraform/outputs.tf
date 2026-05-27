output "app_bastion_public_ip" {
  value = oci_core_instance.app_bastion.public_ip
}

output "app_bastion_private_ip" {
  value = oci_core_instance.app_bastion.private_ip
}

output "db_private_ip" {
  value = oci_core_instance.db.private_ip
}

output "coherence_private_ips" {
  value = oci_core_instance.coherence[*].private_ip
}

output "redis_private_ip" {
  value = oci_core_instance.redis.private_ip
}

output "ssh_proxy_example" {
  value = "ssh -i ../../.ssh/poc_coherence_ed25519 -J opc@${oci_core_instance.app_bastion.public_ip} opc@${oci_core_instance.db.private_ip}"
}
