module "vpc" {
  source = "../../modules/vpc"

  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region

  vpc_cidr = var.vpc_cidr

  public_subnet_cidrs  = var.public_subnet_cidrs
  private_subnet_cidrs = var.private_subnet_cidrs
  availability_zones   = var.availability_zones
}

module "rds" {
  source = "../../modules/rds"

  project_name = var.project_name
  environment  = var.environment

  vpc_id              = module.vpc.vpc_id
  private_subnet_ids  = module.vpc.private_subnet_ids
  allowed_cidr_blocks = var.database_allowed_cidrs

  database_name  = var.database_name
  instance_class = var.database_instance_class

  backup_retention_days = 7
  deletion_protection   = false
  skip_final_snapshot   = true
}

data "aws_caller_identity" "current" {}

module "archive_s3" {
  source = "../../modules/s3"

  project_name   = var.project_name
  environment    = var.environment
  aws_account_id = data.aws_caller_identity.current.account_id
  force_destroy  = true
}

module "loader_ecr" {
  source = "../../modules/ecr"

  project_name = var.project_name
  environment  = var.environment
}

module "loader_ecs" {
  source = "../../modules/ecs"

  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region

  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
  loader_image       = "${module.loader_ecr.repository_url}:latest"

  data_bucket_arn  = module.archive_s3.data_bucket_arn
  data_bucket_name = module.archive_s3.data_bucket_name

  database_secret_arn        = module.rds.database_secret_arn
  database_security_group_id = module.rds.database_security_group_id
}

module "api_ecr" {
  source = "../../modules/api_ecr"

  project_name = var.project_name
  environment  = var.environment
}

module "api_service" {
  source = "../../modules/api_service"

  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region

  vpc_id             = module.vpc.vpc_id
  public_subnet_ids  = module.vpc.public_subnet_ids
  private_subnet_ids = module.vpc.private_subnet_ids

  api_image = "${module.api_ecr.repository_url}:latest"

  database_secret_arn        = module.rds.database_secret_arn
  database_security_group_id = module.rds.database_security_group_id

  container_port = 8080
  desired_count  = 1
}
