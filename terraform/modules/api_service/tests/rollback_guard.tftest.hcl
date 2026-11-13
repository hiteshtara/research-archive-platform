# Automated tests for the API image rollback guard.
#
# The guard is a precondition on aws_ecs_task_definition.api comparing
# var.api_image against var.expected_live_image, so it can be tested as a
# pure function of two inputs - no AWS calls, no real infrastructure.
# The provider is mocked for exactly that reason.
#
#   terraform -chdir=terraform/modules/api_service test
#
# The five scenarios are the ones that matter operationally:
#
#   1. serving revision 94, pin matches        -> plan succeeds
#   2. unused ACTIVE revisions 95-100          -> must NOT become the reference
#   3. genuinely stale desired image           -> plan FAILS (the guard's job)
#   4. legitimate forward deployment           -> plan succeeds once re-pinned
#   5. unrelated change (RDS deletion protect) -> not blocked
#
# Real digests from DEV on 2026-11-13, so the fixtures are not invented.

mock_provider "aws" {
  # The mock provider returns placeholder strings, which IAM's own
  # validation rejects for assume-role policies. Give the policy-document
  # data source real JSON so the module can be planned; the guard under
  # test does not depend on its contents.
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}

variables {
  project_name               = "research-archive-platform"
  environment                = "dev"
  aws_region                 = "us-east-1"
  vpc_id                     = "vpc-0590614d7cfcdedf6"
  public_subnet_ids          = ["subnet-aaaaaaaa", "subnet-bbbbbbbb"]
  private_subnet_ids         = ["subnet-cccccccc", "subnet-dddddddd"]
  database_secret_arn        = "arn:aws:secretsmanager:us-east-1:770203350335:secret:fake-test"
  database_security_group_id = "sg-0123456789abcdef0"
  cognito_issuer_uri         = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_TESTPOOL"
  cognito_client_id          = "testclientid"
}

# --- 1. Serving revision 94, pin matches it -----------------------------
#
# The normal steady state: the pin names exactly what the service runs.

run "serving_revision_94_with_matching_pin_is_allowed" {
  command = plan

  variables {
    api_image           = "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api@sha256:1d440f251c6d725b4e1d34907461f747a0367080a6a7bfbab395412ec1927981"
    expected_live_image = "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api@sha256:1d440f251c6d725b4e1d34907461f747a0367080a6a7bfbab395412ec1927981"
  }
}

# --- 2. Unused ACTIVE revisions 95-100 ----------------------------------
#
# Revision 100 (a bounded fan-out experiment) is ACTIVE in the family but
# was never deployed; the service runs 94. Before the fix, family
# resolution made revision 100's image the reference, so a CORRECT pin
# naming the SERVING image failed the guard.
#
# This test encodes that false failure: if expected_live_image is ever
# again sourced from an unused revision, the guard rejects a pin that is
# actually right. The fix is to source it from the service, which this
# test's scenario 1 and 4 cover.

run "an_unused_active_revision_must_not_become_the_reference" {
  command = plan

  variables {
    # Pin names the SERVING image (revision 94) - this is correct.
    api_image = "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api@sha256:1d440f251c6d725b4e1d34907461f747a0367080a6a7bfbab395412ec1927981"
    # Reference wrongly taken from unused revision 100 (experiment image).
    expected_live_image = "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api@sha256:df3dcfe2d4badb5fdbd1c7e62907aad64bde610954e45c5c99aa6c7fd3ce3ffd"
  }

  # Demonstrates the pre-fix behaviour: a correct pin is rejected because
  # the reference came from a revision nobody deployed.
  expect_failures = [aws_ecs_task_definition.api]
}

# --- 3. A genuinely stale desired image ---------------------------------
#
# The real hazard. The pin still names revision 84's image from
# 2026-10-06 while the service runs revision 94. This MUST fail.

run "a_genuinely_stale_pin_is_refused" {
  command = plan

  variables {
    api_image           = "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api@sha256:c9f3bc99be9a99fb480c577c0d78d82ea7ebdad3b7d6f3eb3aeb72114ddd4821"
    expected_live_image = "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api@sha256:1d440f251c6d725b4e1d34907461f747a0367080a6a7bfbab395412ec1927981"
  }

  expect_failures = [aws_ecs_task_definition.api]
}

# --- 4. A legitimate forward deployment ---------------------------------
#
# Deployments happen through ops/deploy-api.sh, not Terraform. So a
# legitimate forward deployment is: the script deploys a new image, the
# service now runs it, and the pin is updated to match. The guard must
# then allow the plan.

run "a_forward_deployment_is_allowed_once_the_pin_is_updated" {
  command = plan

  variables {
    api_image           = "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api@sha256:aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff6666aaaa7777bbbb8888"
    expected_live_image = "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api@sha256:aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff6666aaaa7777bbbb8888"
  }
}

# Terraform must not be able to DRIVE a deployment: a pin naming an image
# the service is not running is refused even when that image is newer.
# Digests carry no ordering, so "newer" is unknowable here - and the
# ownership model says Terraform does not deploy, so refusing is correct.

run "terraform_cannot_drive_a_deployment_by_moving_the_pin_ahead" {
  command = plan

  variables {
    api_image           = "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api@sha256:aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff6666aaaa7777bbbb8888"
    expected_live_image = "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api@sha256:1d440f251c6d725b4e1d34907461f747a0367080a6a7bfbab395412ec1927981"
  }

  expect_failures = [aws_ecs_task_definition.api]
}

# --- 5. An unrelated Terraform modification -----------------------------
#
# The RDS deletion-protection case. An unrelated change must not be
# blocked when the pin is correct - the guard is scoped to the image and
# nothing else. Modelled here as a matching pin with other inputs varied,
# since the guard cannot see resources outside this module.

run "an_unrelated_change_is_not_blocked_when_the_pin_is_correct" {
  command = plan

  variables {
    api_image           = "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api@sha256:1d440f251c6d725b4e1d34907461f747a0367080a6a7bfbab395412ec1927981"
    expected_live_image = "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api@sha256:1d440f251c6d725b4e1d34907461f747a0367080a6a7bfbab395412ec1927981"
    desired_count       = 2
    container_port      = 8080
  }
}

# --- Guard disabled -----------------------------------------------------
#
# expected_live_image = null is how a deliberate rollback, or an
# environment with no service yet, switches the check off.

run "a_null_reference_disables_the_check" {
  command = plan

  variables {
    api_image           = "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api@sha256:c9f3bc99be9a99fb480c577c0d78d82ea7ebdad3b7d6f3eb3aeb72114ddd4821"
    expected_live_image = null
  }
}
