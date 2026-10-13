output "state_bucket" {
  description = "S3 bucket for remote state."
  value       = aws_s3_bucket.state.id
}

output "lock_table" {
  description = "DynamoDB table for state locking."
  value       = aws_dynamodb_table.lock.name
}

output "state_kms_key_arn" {
  description = "KMS key encrypting the state."
  value       = aws_kms_key.state.arn
}

output "backend_config" {
  description = "Paste into envs/<env>/backend.hcl (partial backend configuration)."
  value       = <<-EOT
    bucket         = "${aws_s3_bucket.state.id}"
    key            = "prod/terraform.tfstate"
    region         = "${var.region}"
    dynamodb_table = "${aws_dynamodb_table.lock.name}"
    encrypt        = true
    kms_key_id     = "${aws_kms_key.state.arn}"
  EOT
}
