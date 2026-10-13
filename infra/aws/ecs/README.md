# Superseded

These task-definition templates were the original hand-written deployment description. Task definitions are now
managed by Terraform (`infra/terraform/modules/ecs`); the CI deploy job fetches the current Terraform-managed revision
and only swaps the image. Kept for reference only.
