output "db_writer_address" {
  description = "Hostname of the RDS writer."
  value       = aws_db_instance.writer.address
}

output "db_writer_identifier" {
  description = "Identifier of the RDS writer."
  value       = aws_db_instance.writer.identifier
}

output "db_reader_address" {
  description = "Hostname of the analytics read replica (null when disabled)."
  value       = try(aws_db_instance.replica[0].address, null)
}

output "db_replica_identifier" {
  description = "Identifier of the read replica (null when disabled)."
  value       = try(aws_db_instance.replica[0].identifier, null)
}

output "db_port" {
  description = "PostgreSQL port."
  value       = aws_db_instance.writer.port
}

output "db_name" {
  description = "Application database name."
  value       = aws_db_instance.writer.db_name
}

output "db_allocated_storage_gib" {
  description = "Initially allocated storage (GiB), used to size the free-storage alarm."
  value       = aws_db_instance.writer.allocated_storage
}

output "db_master_username" {
  description = "RDS master username."
  value       = aws_db_instance.writer.username
}

output "db_master_user_secret_arn" {
  description = "ARN of the RDS-managed master user secret."
  value       = try(aws_db_instance.writer.master_user_secret[0].secret_arn, null)
}

output "redis_replication_group_id" {
  description = "ElastiCache replication group ID."
  value       = aws_elasticache_replication_group.this.id
}

output "redis_primary_endpoint" {
  description = "Primary endpoint address of the replication group."
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "redis_member_cluster_ids" {
  description = "Predictable member cluster IDs (<group>-001, -002, ...) for per-node CloudWatch alarms."
  value       = [for i in range(var.redis_num_cache_clusters) : format("%s-%03d", local.redis_id, i + 1)]
}
