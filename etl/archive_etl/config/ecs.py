"""ECS execution mode: resolve Oracle/PostgreSQL credentials without
requiring a developer's local .env exports.

Current infrastructure fact (see terraform/modules/ecs/main.tf and
terraform/modules/rds/main.tf - not modified by this module): the ECS
loader task definition already injects POSTGRES_HOST/PORT/DB/USER/PASSWORD
as plain container environment variables via ECS's own native `secrets`
resolution against the RDS-managed Secrets Manager secret
(<project>/<environment>/postgres, shape {engine, host, port, dbname,
username, password}) - the platform does that Secrets Manager fetch before
this process ever starts. So the common case for PostgreSQL needs no code
here at all: require_postgres_environment() already sees working
credentials as ordinary environment variables.

This module exists for two cases current infrastructure doesn't cover:
  - A direct Secrets Manager lookup as an explicit fallback (via
    POSTGRES_SECRET_ARN), for contexts where the plain env vars aren't
    pre-populated (e.g. a manual `aws ecs run-task` override, or a future
    Terraform change that passes only a secret ARN).
  - Oracle credentials, which currently have NO Secrets Manager secret
    anywhere in this repo's Terraform - there is no verified secret name
    or JSON shape for Oracle. The {"username", "password", "dsn"} shape
    used below (via ORACLE_SECRET_ARN) is a PROPOSED contract, not a
    confirmed one - verify it against whatever secret is actually
    provisioned before relying on it in a real ECS run. ECS environment
    variables (ORACLE_USER/ORACLE_PASSWORD/ORACLE_DSN) remain fully
    supported and are tried first.
"""

from __future__ import annotations

import json
import os
from collections.abc import Mapping, MutableMapping

from loguru import logger

from archive_etl.config.settings import (
    ConfigurationError,
    require_oracle_environment,
    require_postgres_environment,
)

POSTGRES_SECRET_ARN_VARIABLE = "POSTGRES_SECRET_ARN"
ORACLE_SECRET_ARN_VARIABLE = "ORACLE_SECRET_ARN"

# Verified against terraform/modules/rds/main.tf's
# aws_secretsmanager_secret_version.database.
_POSTGRES_SECRET_FIELD_MAP = {
    "host": "POSTGRES_HOST",
    "port": "POSTGRES_PORT",
    "dbname": "POSTGRES_DB",
    "username": "POSTGRES_USER",
    "password": "POSTGRES_PASSWORD",
}

# NOT verified against any real secret - see module docstring.
_ORACLE_SECRET_FIELD_MAP = {
    "username": "ORACLE_USER",
    "password": "ORACLE_PASSWORD",
    "dsn": "ORACLE_DSN",
}


def _fetch_secret_json(secrets_client, secret_id: str) -> dict[str, object]:
    try:
        response = secrets_client.get_secret_value(SecretId=secret_id)
    except Exception as error:
        raise ConfigurationError(
            f"Failed to read secret '{secret_id}' from Secrets Manager: "
            f"{type(error).__name__}"
        ) from error

    secret_string = response.get("SecretString")
    if not secret_string:
        raise ConfigurationError(
            f"Secret '{secret_id}' has no SecretString value"
        )

    try:
        return json.loads(secret_string)
    except json.JSONDecodeError as error:
        raise ConfigurationError(
            f"Secret '{secret_id}' is not valid JSON"
        ) from error


def _map_secret_fields(
    secret: Mapping[str, object],
    field_map: Mapping[str, str],
    secret_id: str,
) -> dict[str, str]:
    missing = [key for key in field_map if key not in secret]
    if missing:
        raise ConfigurationError(
            f"Secret '{secret_id}' is missing field(s): " + ", ".join(missing)
        )
    return {
        env_name: str(secret[key]) for key, env_name in field_map.items()
    }


def resolve_postgres_credentials(
    secrets_client,
    environ: Mapping[str, str] = os.environ,
) -> dict[str, str]:
    """Resolve PostgreSQL credentials: plain POSTGRES_* environment
    variables first (already true in the current ECS task, whose native
    `secrets` resolution populates these before this process starts),
    falling back to a direct Secrets Manager lookup via
    POSTGRES_SECRET_ARN. Raises ConfigurationError if neither is
    available - --ecs mode never falls through to requiring a local
    export."""
    try:
        return require_postgres_environment(environ)
    except ConfigurationError:
        pass

    secret_arn = environ.get(POSTGRES_SECRET_ARN_VARIABLE)
    if not secret_arn:
        raise ConfigurationError(
            "PostgreSQL credentials are not available as environment "
            f"variables and {POSTGRES_SECRET_ARN_VARIABLE} is not set - "
            "cannot resolve PostgreSQL credentials in --ecs mode"
        )

    logger.info(
        "Resolving PostgreSQL credentials from Secrets Manager secret {}",
        secret_arn,
    )
    secret = _fetch_secret_json(secrets_client, secret_arn)
    resolved = _map_secret_fields(
        secret, _POSTGRES_SECRET_FIELD_MAP, secret_arn
    )
    resolved["POSTGRES_SSLMODE"] = environ.get("POSTGRES_SSLMODE", "prefer")
    return resolved


def resolve_oracle_credentials(
    secrets_client,
    environ: Mapping[str, str] = os.environ,
) -> dict[str, str]:
    """Resolve Oracle credentials: a direct Secrets Manager lookup via
    ORACLE_SECRET_ARN if set (see module docstring - this JSON shape is
    proposed, not verified against a real secret), otherwise plain
    ORACLE_USER/ORACLE_PASSWORD/ORACLE_DSN environment variables. Raises
    ConfigurationError if neither is available."""
    secret_arn = environ.get(ORACLE_SECRET_ARN_VARIABLE)
    if secret_arn:
        logger.info(
            "Resolving Oracle credentials from Secrets Manager secret {}",
            secret_arn,
        )
        secret = _fetch_secret_json(secrets_client, secret_arn)
        return _map_secret_fields(secret, _ORACLE_SECRET_FIELD_MAP, secret_arn)

    try:
        return require_oracle_environment(environ)
    except ConfigurationError as error:
        raise ConfigurationError(
            "Oracle credentials are not available as environment "
            f"variables and {ORACLE_SECRET_ARN_VARIABLE} is not set - "
            "cannot resolve Oracle credentials in --ecs mode"
        ) from error


def configure_ecs_environment(
    secrets_client,
    environ: MutableMapping[str, str] = os.environ,
) -> None:
    """Resolve PostgreSQL and Oracle credentials for --ecs mode and write
    them into `environ` in place, so every existing downstream helper
    (create_postgres_engine(), OracleDataSource, _connect_oracle(), ...)
    keeps working completely unchanged - they already read these same
    variable names via os.environ. Never requires a local .env export:
    fails with a clear ConfigurationError if credentials cannot be
    resolved from either plain environment variables or Secrets Manager."""
    postgres_values = resolve_postgres_credentials(secrets_client, environ)
    environ.update(postgres_values)

    oracle_values = resolve_oracle_credentials(secrets_client, environ)
    environ.update(oracle_values)
