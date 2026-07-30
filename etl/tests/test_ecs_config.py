from __future__ import annotations

import json
import unittest
from unittest.mock import MagicMock

from archive_etl.config import ecs
from archive_etl.config.settings import ConfigurationError


class ResolvePostgresCredentialsTest(unittest.TestCase):
    def test_prefers_plain_environment_variables(self) -> None:
        secrets_client = MagicMock()
        environ = {
            "POSTGRES_HOST": "db.internal",
            "POSTGRES_PORT": "5432",
            "POSTGRES_DB": "research_archive",
            "POSTGRES_USER": "app",
            "POSTGRES_PASSWORD": "hunter2",
        }

        result = ecs.resolve_postgres_credentials(secrets_client, environ)

        self.assertEqual(result["POSTGRES_HOST"], "db.internal")
        secrets_client.get_secret_value.assert_not_called()

    def test_falls_back_to_secrets_manager_when_env_vars_missing(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps(
                {
                    "engine": "postgres",
                    "host": "research-archive-platform-dev-postgres.example",
                    "port": 5432,
                    "dbname": "research_archive",
                    "username": "app_user",
                    "password": "s3cr3t",
                }
            )
        }
        environ = {"POSTGRES_SECRET_ARN": "arn:aws:secretsmanager:...:postgres"}

        result = ecs.resolve_postgres_credentials(secrets_client, environ)

        self.assertEqual(
            result["POSTGRES_HOST"],
            "research-archive-platform-dev-postgres.example",
        )
        self.assertEqual(result["POSTGRES_PORT"], "5432")
        self.assertEqual(result["POSTGRES_DB"], "research_archive")
        self.assertEqual(result["POSTGRES_USER"], "app_user")
        self.assertEqual(result["POSTGRES_PASSWORD"], "s3cr3t")
        secrets_client.get_secret_value.assert_called_once_with(
            SecretId="arn:aws:secretsmanager:...:postgres"
        )

    def test_raises_when_neither_env_vars_nor_secret_arn_available(self) -> None:
        secrets_client = MagicMock()

        with self.assertRaises(ConfigurationError):
            ecs.resolve_postgres_credentials(secrets_client, {})

        secrets_client.get_secret_value.assert_not_called()


class ResolveOracleCredentialsTest(unittest.TestCase):
    def test_prefers_secrets_manager_when_secret_arn_is_configured(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps(
                {
                    "username": "kcuser",
                    "password": "hunter2",
                    "dsn": "kuali-oracle.bu.edu:1521/KCPROD",
                }
            )
        }
        environ = {
            "ORACLE_SECRET_ARN": "arn:aws:secretsmanager:...:oracle",
            "ORACLE_USER": "should-be-ignored",
        }

        result = ecs.resolve_oracle_credentials(secrets_client, environ)

        self.assertEqual(result["ORACLE_USER"], "kcuser")
        self.assertEqual(result["ORACLE_PASSWORD"], "hunter2")
        self.assertEqual(result["ORACLE_DSN"], "kuali-oracle.bu.edu:1521/KCPROD")

    def test_falls_back_to_plain_environment_variables(self) -> None:
        secrets_client = MagicMock()
        environ = {
            "ORACLE_USER": "kcuser",
            "ORACLE_PASSWORD": "hunter2",
            "ORACLE_DSN": "kuali-oracle.bu.edu:1521/KCPROD",
        }

        result = ecs.resolve_oracle_credentials(secrets_client, environ)

        self.assertEqual(result["ORACLE_USER"], "kcuser")
        secrets_client.get_secret_value.assert_not_called()

    def test_raises_when_neither_secret_arn_nor_env_vars_available(self) -> None:
        secrets_client = MagicMock()

        with self.assertRaises(ConfigurationError):
            ecs.resolve_oracle_credentials(secrets_client, {})


class MissingSecretsTest(unittest.TestCase):
    def test_raises_when_secret_has_no_secret_string(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {}
        environ = {"POSTGRES_SECRET_ARN": "arn:...:postgres"}

        with self.assertRaises(ConfigurationError):
            ecs.resolve_postgres_credentials(secrets_client, environ)

    def test_raises_when_secret_is_not_valid_json(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": "not-json"
        }
        environ = {"POSTGRES_SECRET_ARN": "arn:...:postgres"}

        with self.assertRaises(ConfigurationError):
            ecs.resolve_postgres_credentials(secrets_client, environ)

    def test_raises_when_secret_is_missing_expected_fields(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps({"host": "db.internal"})
        }
        environ = {"POSTGRES_SECRET_ARN": "arn:...:postgres"}

        with self.assertRaises(ConfigurationError) as raised:
            ecs.resolve_postgres_credentials(secrets_client, environ)

        message = str(raised.exception)
        self.assertIn("port", message)
        self.assertIn("dbname", message)

    def test_raises_when_secrets_manager_call_itself_fails(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.side_effect = RuntimeError("network error")
        environ = {"POSTGRES_SECRET_ARN": "arn:...:postgres"}

        with self.assertRaises(ConfigurationError):
            ecs.resolve_postgres_credentials(secrets_client, environ)


class ConfigureEcsEnvironmentTest(unittest.TestCase):
    def test_writes_resolved_credentials_into_environ_in_place(self) -> None:
        secrets_client = MagicMock()
        environ = {
            "POSTGRES_HOST": "db.internal",
            "POSTGRES_PORT": "5432",
            "POSTGRES_DB": "research_archive",
            "POSTGRES_USER": "app",
            "POSTGRES_PASSWORD": "hunter2",
            "ORACLE_USER": "kcuser",
            "ORACLE_PASSWORD": "hunter2",
            "ORACLE_DSN": "kuali-oracle.bu.edu:1521/KCPROD",
        }

        ecs.configure_ecs_environment(secrets_client, environ)

        self.assertEqual(environ["POSTGRES_HOST"], "db.internal")
        self.assertEqual(environ["ORACLE_USER"], "kcuser")

    def test_raises_cleanly_when_postgres_credentials_unavailable(self) -> None:
        secrets_client = MagicMock()
        environ = {
            "ORACLE_USER": "kcuser",
            "ORACLE_PASSWORD": "hunter2",
            "ORACLE_DSN": "kuali-oracle.bu.edu:1521/KCPROD",
        }

        with self.assertRaises(ConfigurationError):
            ecs.configure_ecs_environment(secrets_client, environ)


if __name__ == "__main__":
    unittest.main()
