# Research Archive Platform

Read-only archive and reporting platform for legacy BU research administration data.

## Data domains

- Protocols
- Awards
- Institutional Proposals
- Negotiations
- Subawards
- Documents

## Data flow

BU Oracle and BU files are accessible only from a local computer connected to the BU VPN.

1. Python runs locally and extracts approved data.
2. Data is validated and written to CSV or Parquet.
3. Approved exports are uploaded to Amazon S3.
4. Data is loaded into Amazon RDS PostgreSQL.
5. A Spring Boot application provides search, reporting, and document access.

## Technology

- Python ETL
- Spring Boot
- PostgreSQL
- Amazon S3
- Amazon RDS
- Amazon ECS Fargate
- Terraform
