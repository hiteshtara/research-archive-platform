from __future__ import annotations

import pytest

from archive_etl.config.settings import (
    ConfigurationError,
    get_aws_region,
    get_data_bucket_name,
    require_oracle_environment,
    require_postgres_environment,
)


def test_require_oracle_environment_returns_values_when_present() -> None:
    environ = {
        "ORACLE_USER": "user",
        "ORACLE_PASSWORD": "password",
        "ORACLE_DSN": "dsn",
    }

    assert require_oracle_environment(environ) == environ


def test_require_oracle_environment_lists_all_missing_variables() -> None:
    with pytest.raises(ConfigurationError) as excinfo:
        require_oracle_environment({"ORACLE_USER": "user"})

    assert "ORACLE_PASSWORD" in str(excinfo.value)
    assert "ORACLE_DSN" in str(excinfo.value)


def test_require_postgres_environment_defaults_sslmode_to_prefer() -> None:
    environ = {
        "POSTGRES_HOST": "db.example.com",
        "POSTGRES_PORT": "5432",
        "POSTGRES_DB": "archive",
        "POSTGRES_USER": "archive_admin",
        "POSTGRES_PASSWORD": "secret",
    }

    values = require_postgres_environment(environ)

    assert values["POSTGRES_SSLMODE"] == "prefer"


def test_require_postgres_environment_honors_explicit_sslmode() -> None:
    environ = {
        "POSTGRES_HOST": "db.example.com",
        "POSTGRES_PORT": "5432",
        "POSTGRES_DB": "archive",
        "POSTGRES_USER": "archive_admin",
        "POSTGRES_PASSWORD": "secret",
        "POSTGRES_SSLMODE": "require",
    }

    values = require_postgres_environment(environ)

    assert values["POSTGRES_SSLMODE"] == "require"


def test_require_postgres_environment_raises_on_missing_variables() -> None:
    with pytest.raises(ConfigurationError):
        require_postgres_environment({})


def test_get_aws_region_defaults_when_unset() -> None:
    assert get_aws_region({}) == "us-east-1"


def test_get_aws_region_honors_explicit_value() -> None:
    assert get_aws_region({"AWS_REGION": "us-west-2"}) == "us-west-2"


def test_get_data_bucket_name_raises_when_unset() -> None:
    with pytest.raises(ConfigurationError):
        get_data_bucket_name({})


def test_get_data_bucket_name_returns_configured_value() -> None:
    environ = {"DATA_BUCKET_NAME": "my-bucket"}

    assert get_data_bucket_name(environ) == "my-bucket"
