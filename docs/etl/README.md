# ETL documentation

The ETL preserves approved Kuali research-administration data in the
Research Archive. It reads from Oracle or approved IRB exports, validates the
result, and writes archive-owned PostgreSQL tables and private S3 objects. It
never writes back to Oracle.

Start with the document that matches your task:

| Document | Use it when |
| --- | --- |
| [Getting started](getting-started.md) | You are setting up the ETL or running your first read-only smoke test. |
| [Operations guide](operations.md) | You need to run, verify, recover, or deploy a load. |
| [Command and configuration reference](reference.md) | You need the exact command, environment variable, module, or execution mode. |
| [Architecture](architecture.md) | You need to understand the data flows, transaction boundaries, migrations, batching, and safety model. |

Domain-specific material remains authoritative for details that do not apply
to every loader:

- [Protocol Oracle loader](../PROTOCOL_ORACLE_LOADER.md)
- [ETL batch framework](../architecture/ETL_BATCH_FRAMEWORK.md)
- [Subaward attachment archive](../SUBAWARD_ATTACHMENT_ARCHIVE.md)
- [Award attachment ECS execution](../AWARD_ATTACHMENT_ECS_EXECUTION.md)
- [Attachment module inventory](../ATTACHMENT_MODULE_INVENTORY.md)
- [Oracle operator runbook](../runbooks/ORACLE.md)

