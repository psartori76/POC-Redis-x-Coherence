output "app_bastion_public_ip" {
  value = oci_core_instance.app_bastion.public_ip
}

output "app_bastion_private_ip" {
  value = oci_core_instance.app_bastion.private_ip
}

output "db_private_ip" {
  value = oci_core_instance.db.private_ip
}

output "redis_private_ip" {
  value = oci_core_instance.redis.private_ip
}

output "coherence_private_ip" {
  value = oci_core_instance.coherence.private_ip
}

output "ssh_tunnel_command" {
  value = "ssh -i <private-key-file> -o StrictHostKeyChecking=no -N -L 8081:127.0.0.1:8080 -L 30000:${oci_core_instance.coherence.private_ip}:30000 opc@${oci_core_instance.app_bastion.public_ip}"
}

output "console_url_after_tunnel" {
  value = "http://localhost:8081/console"
}

output "observability_url_after_tunnel" {
  value = "http://localhost:8081/management-proxy/cluster"
}

output "coherence_rest_url_after_tunnel" {
  value = "http://localhost:30000/management/coherence/cluster"
}

output "health_check_via_bastion" {
  value = "ssh -i <private-key-file> opc@${oci_core_instance.app_bastion.public_ip} 'curl -fsS http://127.0.0.1:8080/health'"
}
