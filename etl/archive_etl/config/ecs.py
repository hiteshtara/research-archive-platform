"""ECS execution mode: resolve Oracle/PostgreSQL credentials from AWS
Secrets Manager only.

Security requirement this module exists to satisfy: both PostgreSQL and
Oracle credentials must be stored in Secrets Manager, and in --ecs mode
username/password are ALWAYS fetched from Secrets Manager - there is no
environment-variable fallback for the credential values themselves. A
plaintext password sitting in the container's environment block is
exactly what this module is designed to avoid. Only the *secret
identifier* (an ARN or name - not a credential) is read from a plain,
non-secret environment variable:

    POSTGRES_SECRET_ID   - required in --ecs mode
    ORACLE_SECRET_ID      - required in --ecs mode (except --migrate-only,
                             which never touches Oracle at all)

PostgreSQL secret contract (verified against
terraform/modules/rds/main.tf's aws_secretsmanager_secret_version.database
- the secret already provisioned at
arn:aws:secretsmanager:us-east-1:770203350335:secret:research-archive-platform/dev/postgres-4k6Ngz):
    {"engine", "host", "port", "dbname", "username", "password"}
username/password are always required from the secret - never from an
environment variable, even if one happens to be set. host/port/dbname
("database" is also accepted as a synonym for "dbname") are read from
the secret when present; if the secret does not include one of them,
this falls back to the corresponding plain POSTGRES_HOST/POSTGRES_PORT/
POSTGRES_DB environment variable - those are connection routing info,
not credentials, so a plain environment variable is an acceptable source
for them specifically, and only for them.

localhost/127.0.0.1 and port 15432 are always rejected as the resolved
PostgreSQL host/port in --ecs mode, regardless of source - this loader's
whole reason for existing is that the private RDS endpoint is not
reachable via a local tunnel; --ecs mode resolving to a loopback address
would silently defeat that and is treated as a configuration error.

Oracle secret contract (PROPOSED - no Oracle secret exists in this repo's
Terraform yet; see docs/AWARD_ATTACHMENT_ECS_EXECUTION.md for the exact,
safe `aws secretsmanager create-secret` command to provision one,
recommended name research-archive-platform/dev/oracle):
    {"username": "...", "password": "...", "dsn": "..."}
All three fields are always required from the secret in --ecs mode -
never ORACLE_USER/ORACLE_PASSWORD/ORACLE_DSN environment variables, even
if one happens to be set.

Never logs secret values. Only ever logs the secret's identifier (ARN or
name) via a bound `secret_id` field - never its content, never the DSN,
never a resolved host/port/username value. See structured_logging.py's
fixed field allow-list, which has no field for arbitrary secret content.
"""

from __future__ import annotations

import ipaddress
import json
import os
from collections.abc import Mapping, MutableMapping
from typing import Any

from botocore.exceptions import ClientError
from loguru import logger

from archive_etl.config.settings import ConfigurationError

POSTGRES_SECRET_ID_VARIABLE = "POSTGRES_SECRET_ID"
ORACLE_SECRET_ID_VARIABLE = "ORACLE_SECRET_ID"

# host/port/dbname are optional in the secret (see module docstring) -
# handled with an environment-variable fallback, not a required-key
# check. "database" is accepted as a synonym for "dbname".
_POSTGRES_DBNAME_KEYS = ("dbname", "database")

_ORACLE_REQUIRED_KEYS = ("username", "password", "dsn")

# Rejected outright as a resolved PostgreSQL host/port in --ecs mode -
# see module docstring.
_REJECTED_POSTGRES_HOSTS = frozenset({"localhost", "127.0.0.1", "::1"})
_REJECTED_POSTGRES_PORT = "15432"


class SecretNotFoundError(ConfigurationError):
    """The named/ARN'd secret does not exist in Secrets Manager."""


class SecretAccessDeniedError(ConfigurationError):
    """The caller's IAM identity is not authorized to read this secret."""


class SecretInvalidJsonError(ConfigurationError):
    """The secret's SecretString is not a JSON object."""


class SecretMissingKeyError(ConfigurationError):
    """The secret JSON is missing a required key."""


class SecretEmptyValueError(ConfigurationError):
    """The secret JSON has a required key, but its value is empty."""


def _fetch_secret_json(
    secrets_client: Any, secret_id: str, *, label: str
) -> dict[str, Any]:
    """Fetch and parse a secret's JSON content. Never includes the
    secret's content - or the underlying botocore exception's full text -
    in any exception message, only the secret identifier and the failure
    category."""
    try:
        response = secrets_client.get_secret_value(SecretId=secret_id)
    except ClientError as error:
        code = error.response.get("Error", {}).get("Code", "")
        if code == "ResourceNotFoundException":
            raise SecretNotFoundError(
                f"{label} '{secret_id}' was not found in Secrets Manager"
            ) from None
        if code in ("AccessDeniedException", "UnauthorizedException"):
            raise SecretAccessDeniedError(
                f"Access denied reading {label} '{secret_id}' from Secrets "
                "Manager - check the task role's secretsmanager:GetSecretValue "
                "permission for this exact secret"
            ) from None
        raise ConfigurationError(
            f"Failed to read {label} '{secret_id}' from Secrets Manager "
            f"({code or type(error).__name__})"
        ) from None
    except Exception as error:
        raise ConfigurationError(
            f"Failed to read {label} '{secret_id}' from Secrets Manager "
            f"({type(error).__name__})"
        ) from None

    secret_string = response.get("SecretString")
    if not secret_string:
        raise ConfigurationError(
            f"{label} '{secret_id}' has no SecretString value"
        )

    try:
        parsed = json.loads(secret_string)
    except json.JSONDecodeError:
        raise SecretInvalidJsonError(
            f"{label} '{secret_id}' is not valid JSON"
        ) from None

    if not isinstance(parsed, dict):
        raise SecretInvalidJsonError(
            f"{label} '{secret_id}' JSON must be an object"
        )
    return parsed


def _require_secret_value(
    secret: Mapping[str, Any], key: str, secret_id: str, label: str
) -> str:
    if key not in secret:
        raise SecretMissingKeyError(
            f"{label} '{secret_id}' is missing required key '{key}'"
        )
    value = secret[key]
    if value is None or (isinstance(value, str) and value.strip() == ""):
        raise SecretEmptyValueError(
            f"{label} '{secret_id}' has an empty value for required key "
            f"'{key}'"
        )
    return str(value)


def _optional_secret_or_env(
    secret: Mapping[str, Any],
    keys: tuple[str, ...],
    environ: Mapping[str, str],
    env_var_name: str,
    *,
    secret_id: str,
    label: str,
) -> str:
    """Prefer the secret's own field (checking each of `keys` in order,
    e.g. "dbname" then "database"); fall back to a plain, non-secret
    environment variable when the secret doesn't include it at all - see
    module docstring for why this is acceptable for host/port/dbname
    specifically (routing info, not credentials) but never for
    username/password."""
    for key in keys:
        if key in secret and secret[key] not in (None, ""):
            return str(secret[key])

    value = environ.get(env_var_name)
    if not value:
        raise ConfigurationError(
            f"{label} '{secret_id}' does not include "
            f"{'/'.join(keys)}, and {env_var_name} is not set - cannot "
            "resolve PostgreSQL connection routing"
        )
    return value


def _reject_loopback_postgres_target(host: str, port: str) -> None:
    """--ecs mode must connect directly to the private RDS endpoint - a
    resolved host/port pointing at loopback would silently reintroduce
    the localhost-tunnel pattern this loader is built to avoid."""
    normalized_host = host.strip().lower()
    is_loopback = normalized_host in _REJECTED_POSTGRES_HOSTS
    if not is_loopback:
        try:
            is_loopback = ipaddress.ip_address(normalized_host).is_loopback
        except ValueError:
            is_loopback = False

    if is_loopback:
        raise ConfigurationError(
            f"Resolved PostgreSQL host '{host}' is a loopback address - "
            "--ecs mode must connect directly to the private RDS "
            "endpoint, not localhost"
        )
    if port.strip() == _REJECTED_POSTGRES_PORT:
        raise ConfigurationError(
            f"Resolved PostgreSQL port '{port}' is the local SSM-tunnel "
            "port - --ecs mode must connect directly to the private RDS "
            "endpoint, not a local tunnel"
        )


def resolve_postgres_credentials(
    secrets_client: Any,
    environ: Mapping[str, str] = os.environ,
) -> dict[str, str]:
    """Resolve PostgreSQL credentials for --ecs mode. username/password
    always come from the POSTGRES_SECRET_ID secret - there is no
    environment-variable fallback for them. host/port/dbname come from
    the secret when present, otherwise from the plain POSTGRES_HOST/
    POSTGRES_PORT/POSTGRES_DB environment variables. Rejects a resolved
    host/port that points at loopback (see module docstring)."""
    secret_id = environ.get(POSTGRES_SECRET_ID_VARIABLE)
    if not secret_id:
        raise ConfigurationError(
            f"{POSTGRES_SECRET_ID_VARIABLE} is not set - PostgreSQL "
            "credentials must be resolved from AWS Secrets Manager in "
            "--ecs mode, never from a local export"
        )

    logger.bind(secret_id=secret_id).info(
        "Resolving PostgreSQL credentials from Secrets Manager"
    )
    secret = _fetch_secret_json(secrets_client, secret_id, label="PostgreSQL secret")

    username = _require_secret_value(secret, "username", secret_id, "PostgreSQL secret")
    password = _require_secret_value(secret, "password", secret_id, "PostgreSQL secret")
    host = _optional_secret_or_env(
        secret, ("host",), environ, "POSTGRES_HOST",
        secret_id=secret_id, label="PostgreSQL secret",
    )
    port = _optional_secret_or_env(
        secret, ("port",), environ, "POSTGRES_PORT",
        secret_id=secret_id, label="PostgreSQL secret",
    )
    dbname = _optional_secret_or_env(
        secret, _POSTGRES_DBNAME_KEYS, environ, "POSTGRES_DB",
        secret_id=secret_id, label="PostgreSQL secret",
    )

    _reject_loopback_postgres_target(host, port)

    return {
        "POSTGRES_HOST": host,
        "POSTGRES_PORT": port,
        "POSTGRES_DB": dbname,
        "POSTGRES_USER": username,
        "POSTGRES_PASSWORD": password,
        "POSTGRES_SSLMODE": environ.get("POSTGRES_SSLMODE", "prefer"),
    }


def resolve_oracle_credentials(
    secrets_client: Any,
    environ: Mapping[str, str] = os.environ,
) -> dict[str, str]:
    """Resolve Oracle credentials for --ecs mode from the
    ORACLE_SECRET_ID secret. All three fields (username, password, dsn)
    are always required from the secret - there is no environment-
    variable fallback for any of them in --ecs mode. Never logs the DSN
    or any credential value - only the secret identifier."""
    secret_id = environ.get(ORACLE_SECRET_ID_VARIABLE)
    if not secret_id:
        raise ConfigurationError(
            f"{ORACLE_SECRET_ID_VARIABLE} is not set - Oracle credentials "
            "must be resolved from AWS Secrets Manager in --ecs mode, "
            "never from a local export"
        )

    logger.bind(secret_id=secret_id).info(
        "Resolving Oracle credentials from Secrets Manager"
    )
    secret = _fetch_secret_json(secrets_client, secret_id, label="Oracle secret")

    values = {
        key: _require_secret_value(secret, key, secret_id, "Oracle secret")
        for key in _ORACLE_REQUIRED_KEYS
    }

    return {
        "ORACLE_USER": values["username"],
        "ORACLE_PASSWORD": values["password"],
        "ORACLE_DSN": values["dsn"],
    }


def configure_ecs_environment(
    secrets_client: Any,
    environ: MutableMapping[str, str] = os.environ,
    *,
    include_oracle: bool = True,
) -> None:
    """Resolve PostgreSQL (and, unless include_oracle=False - see
    --migrate-only) Oracle credentials for --ecs mode and write them into
    `environ` in place, so every existing downstream helper
    (create_postgres_engine(), OracleDataSource, _connect_oracle(), ...)
    keeps working unchanged - they already read these same variable names
    via os.environ. Credentials are held only in this process's memory
    for the task's lifetime; nothing here ever writes them to disk.
    Raises ConfigurationError (or a more specific subclass) if a required
    secret cannot be resolved - --ecs mode never falls through to
    requiring a local export."""
    postgres_values = resolve_postgres_credentials(secrets_client, environ)
    environ.update(postgres_values)

    if include_oracle:
        oracle_values = resolve_oracle_credentials(secrets_client, environ)
        environ.update(oracle_values)
