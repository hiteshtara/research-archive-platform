# Troubleshooting

## Connection refused localhost:15432

The AWS SSM tunnel is not running.

Restart the tunnel.

-------------------------------------------------------------------------------

## DuplicateTable

Migration already applied manually.

Update schema_migration.

-------------------------------------------------------------------------------

## Oracle metadata

Never guess.

Run proposal_columns.sql first.

-------------------------------------------------------------------------------

## Maven

Backend module is api/

Run

cd api

mvn test

