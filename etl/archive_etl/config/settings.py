from __future__ import annotations

import os
from collections.abc import Mapping

# Central place for environment-variable configuration. Kept dependency-free
# (stdlib only) so every module in the ETL - loaders, pipeline sources,
# scripts - can import it without pulling in extras like SQLAlchemy or yaml.


class ConfigurationError(RuntimeError):
    """Raised when required ETL configuration is missing or invalid."""


ORACLE_REQUIRED_VARIABLES = ["ORACLE_USER", "ORACLE_PASSWORD", "ORACLE_DSN"]

POSTGRES_REQUIRED_VARIABLES = [
    "POSTGRES_HOST",
    "POSTGRES_PORT",
    "POSTGRES_DB",
    "POSTGRES_USER",
    "POSTGRES_PASSWORD",
]

DEFAULT_POSTGRES_SSLMODE = "prefer"
DEFAULT_AWS_REGION = "us-east-1"


def _require(names: list[str], environ: Mapping[str, str]) -> dict[str, str]:
    missing = [name for name in names if not environ.get(name)]
    if missing:
        raise ConfigurationError(
            "Missing required environment variable(s): " + ", ".join(missing)
        )
    return {name: environ[name] for name in names}


def require_oracle_environment(
    environ: Mapping[str, str] = os.environ,
) -> dict[str, str]:
    """Validate ORACLE_USER/ORACLE_PASSWORD/ORACLE_DSN are set and return them."""
    return _require(ORACLE_REQUIRED_VARIABLES, environ)


def require_postgres_environment(
    environ: Mapping[str, str] = os.environ,
) -> dict[str, str]:
    """Validate the required POSTGRES_* variables and return them.

    POSTGRES_SSLMODE is optional and defaults to "prefer" (the same default
    libpq/psycopg use), so it is always present in the returned dict.
    """
    values = _require(POSTGRES_REQUIRED_VARIABLES, environ)
    values["POSTGRES_SSLMODE"] = environ.get(
        "POSTGRES_SSLMODE", DEFAULT_POSTGRES_SSLMODE
    )
    return values


def get_aws_region(environ: Mapping[str, str] = os.environ) -> str:
    return environ.get("AWS_REGION", DEFAULT_AWS_REGION)


def get_data_bucket_name(environ: Mapping[str, str] = os.environ) -> str:
    value = environ.get("DATA_BUCKET_NAME")
    if not value:
        raise ConfigurationError(
            "Missing required environment variable: DATA_BUCKET_NAME"
        )
    return value
