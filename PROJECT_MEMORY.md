# Research Archive Platform
## Engineering Memory

This document is the long-term memory of the project: status history, data
grain validation, and incident lessons. For architecture, commands, and
coding conventions, see `CLAUDE.md` (or `AGENTS.md` for non-Claude agents) —
this file intentionally does not repeat that content.

Boston University Research Archive Platform. Preserve historical Kuali
Research Administration data after retirement of the legacy Kuali system. The
archive is read-only.

-------------------------------------------------------------------------------
CURRENT STATUS
-------------------------------------------------------------------------------

Completed

✗ Protocol Archive — a second, independent human-subjects archive that was
  under development as a possible replacement for legacy IRB. Removed in
  full (API, UI, ETL loaders/Oracle SQL, forward-only schema-removal
  migration V032). See `docs/DECISIONS.md` for the reversal and rationale.

✓ Legacy IRB — preserved as the sole human-subjects/protocol domain; no
  longer considered deprecated now that Protocol Archive was removed rather
  than reaching feature parity.

✓ Award Archive

✓ Global Search

✓ Award Workspace

✓ Award History

✓ Award Funding

✓ Award Amounts

✓ Award People

Proposal

Completed

✓ Proposal database design

✓ Proposal migration V015

✓ proposal_version table

✓ proposal_award table

✓ Proposal DTOs

✓ Proposal extraction SQL

Current Work

Oracle Proposal export

proposal_versions.csv

proposal_people.csv

Proposal ETL

Next

ProposalArchiveRepository

ProposalService

ProposalController

Proposal Workspace

Proposal Search

Award → Proposal navigation

(Architecture, research object model, development order, coding rules, and
commands now live in `CLAUDE.md` — not repeated here.)

-------------------------------------------------------------------------------
DATA GRAIN VALIDATION
-------------------------------------------------------------------------------

Award (validated): 281,591 raw rows in `archive.award_version`; 43,057
distinct `award_number`; 281,455 distinct (`award_number`, `sequence_number`);
0 duplicate `award_id` rows. See `CLAUDE.md` for the grain rules this
validates.

-------------------------------------------------------------------------------
KNOWN LESSONS
-------------------------------------------------------------------------------

Award implementation is the reference.

Proposal should mirror Award.

Proposal is the central research object.

Never guess Oracle metadata.

Always verify Oracle schema first.

ETL should be completed before Repository.

Repository before Service.

Service before Controller.

Controller before React.

-------------------------------------------------------------------------------
FUTURE MODULES
-------------------------------------------------------------------------------

Negotiation Archive

Subaward Archive

Agreement Archive

Investigator Workspace

Analytics

Timeline

Cross-object relationships

-------------------------------------------------------------------------------
LONG TERM GOAL
-------------------------------------------------------------------------------

Create a complete historical archive of Boston University research
administration data.

The application should eventually replace the legacy Kuali portal for historical
research records.

# Research Archive Platform
## Subaward Attachment Download Troubleshooting Guide

This document captures the complete troubleshooting process for enabling Subaward Attachment downloads from S3.

**Caution (added 2026-08-01):** the AWS account ID appearing throughout
this historical guide (`589744711110`) is not BU's dev account
(`770203350335`) — it belongs to a personal AWS account that happens to
have identically-named ECS/ECR/S3 resources, discovered while
investigating an unrelated deployment incident (see
`docs/architecture/AWARD_IMPLEMENTATION_ROADMAP.md`'s "Eleventh
same-day follow-up"). The commands and ARNs below are preserved as a
historical record of how this specific issue was diagnosed and are not
being rewritten — but if you're using this guide to troubleshoot a
*live* issue today, verify `aws sts get-caller-identity` shows
`770203350335` first, and do not assume the ARNs/bucket names below are
still current or correct for that account.

---

# 1. Verify ECS Service

Determine which task definition is currently running.

```bash
aws ecs describe-services \
  --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --query 'services[0].taskDefinition' \
  --output text
```

---

# 2. Verify Environment Variables

Ensure the API task definition contains the required environment variables.

```bash
aws ecs describe-task-definition \
  --task-definition research-archive-platform-dev-api:4 \
  --query 'taskDefinition.containerDefinitions[0].environment' \
  --output table
```

Expected output should include:

| Name | Value |
|------|-------|
| ARCHIVE_DOCUMENTS_BUCKET | research-archive-platform-dev-documents-589744711110 |
| AWS_REGION | us-east-1 |

---

# 3. View API Logs

Using helper script:

```bash
./ops/logs-api.sh
```

Or directly through CloudWatch:

```bash
aws logs tail /ecs/research-archive-platform-dev-api \
    --since 5m \
    --follow
```

---

# 4. Initial Failure

The backend originally failed with:

```
IllegalStateException:
ARCHIVE_DOCUMENTS_BUCKET is not configured
```

## Resolution

Added the following ECS environment variables:

```
ARCHIVE_DOCUMENTS_BUCKET=research-archive-platform-dev-documents-589744711110
AWS_REGION=us-east-1
```

No code changes required.

---

# 5. Second Failure

After fixing the environment variables, downloads still returned HTTP 403.

CloudWatch showed:

```
software.amazon.awssdk.services.s3.model.S3Exception

User:
arn:aws:sts::589744711110:assumed-role/research-archive-platform-dev-api-task-role

is not authorized to perform:

s3:GetObject

because no identity-based policy allows the s3:GetObject action.
```

This confirmed:

- Bucket exists
- Object exists
- API found the object
- IAM permission was missing

---

# 6. Attach IAM Policy

Grant the ECS task role permission to read archived documents.

```bash
aws iam put-role-policy \
  --role-name research-archive-platform-dev-api-task-role \
  --policy-name ReadSubawardArchiveDocuments \
  --policy-document '{
    "Version":"2012-10-17",
    "Statement":[
      {
        "Sid":"ReadSubawardArchiveDocuments",
        "Effect":"Allow",
        "Action":"s3:GetObject",
        "Resource":"arn:aws:s3:::research-archive-platform-dev-documents-589744711110/test/subawards/*"
      }
    ]
  }'
```

---

# 7. Verify IAM Policy

```bash
aws iam get-role-policy \
  --role-name research-archive-platform-dev-api-task-role \
  --policy-name ReadSubawardArchiveDocuments
```

Expected:

```json
{
  "PolicyName": "ReadSubawardArchiveDocuments",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::research-archive-platform-dev-documents-589744711110/test/subawards/*"
    }
  ]
}
```

---

# 8. Optional IAM Simulation

Useful if downloads still fail.

```bash
aws iam simulate-principal-policy \
  --policy-source-arn arn:aws:iam::589744711110:role/research-archive-platform-dev-api-task-role \
  --action-names s3:GetObject \
  --resource-arns arn:aws:s3:::research-archive-platform-dev-documents-589744711110/test/subawards/94202/495672/VC_Screen_Resolution_KC_4276.pdf
```

Expected:

```
EvalDecision: allowed
```

---

# 9. Final Result

Everything is now working successfully.

✅ ECS Task Definition updated

✅ Environment variables configured

✅ S3 bucket configured

✅ Attachments uploaded

✅ PostgreSQL metadata synchronized

✅ Download endpoint operational

✅ IAM policy attached

✅ Attachment downloads working

---

# Remaining Cleanup

Move the IAM policy into Terraform.

The role contains:

```
ManagedBy = Terraform
```

A future Terraform apply could overwrite manually attached inline policies.

The following permission should be added to Terraform:

```
Action:
    s3:GetObject

Resource:
    arn:aws:s3:::research-archive-platform-dev-documents-589744711110/test/subawards/*
```

Once added, remove the manually attached inline policy if desired.

---

# Lessons Learned

1. Verify environment variables before debugging code.
2. Read the complete CloudWatch exception.
3. A 403 from S3 is almost always an IAM or bucket policy issue.
4. `aws iam simulate-principal-policy` is invaluable for debugging IAM.
5. Always codify IAM changes in Terraform to prevent configuration drift.
