from __future__ import annotations

import json
import unittest
from unittest.mock import MagicMock, patch

from botocore.exceptions import ClientError

from archive_etl.config import ecs
from archive_etl.config.settings import ConfigurationError


def _client_error(code: str) -> ClientError:
    return ClientError(
        error_response={"Error": {"Code": code, "Message": "boom"}},
        operation_name="GetSecretValue",
    )


class ResolvePostgresCredentialsTest(unittest.TestCase):
    def _full_secret(self, **overrides: object) -> MagicMock:
        payload = {
            "engine": "postgres",
            "host": "research-archive-platform-dev-postgres.example",
            "port": 5432,
            "dbname": "research_archive",
            "username": "app_user",
            "password": "s3cr3t",
        }
        payload.update(overrides)
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps(payload)
        }
        return secrets_client

    def test_username_and_password_always_come_from_the_secret(self) -> None:
        secrets_client = self._full_secret()
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        result = ecs.resolve_postgres_credentials(secrets_client, environ)

        self.assertEqual(result["POSTGRES_USER"], "app_user")
        self.assertEqual(result["POSTGRES_PASSWORD"], "s3cr3t")
        self.assertEqual(result["POSTGRES_HOST"], "research-archive-platform-dev-postgres.example")
        self.assertEqual(result["POSTGRES_PORT"], "5432")
        self.assertEqual(result["POSTGRES_DB"], "research_archive")

    def test_environment_username_and_password_are_never_used(self) -> None:
        # Even if plain POSTGRES_USER/POSTGRES_PASSWORD happen to be set,
        # --ecs mode must never read them - only the secret's own values.
        secrets_client = self._full_secret(username="secret_user", password="secret_pass")
        environ = {
            "POSTGRES_SECRET_ID": "arn:...:postgres",
            "POSTGRES_USER": "env_user_should_be_ignored",
            "POSTGRES_PASSWORD": "env_pass_should_be_ignored",
        }

        result = ecs.resolve_postgres_credentials(secrets_client, environ)

        self.assertEqual(result["POSTGRES_USER"], "secret_user")
        self.assertEqual(result["POSTGRES_PASSWORD"], "secret_pass")

    def test_database_alias_is_accepted_in_place_of_dbname(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps(
                {
                    "host": "db.internal",
                    "port": 5432,
                    "database": "research_archive",
                    "username": "app_user",
                    "password": "s3cr3t",
                }
            )
        }
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        result = ecs.resolve_postgres_credentials(secrets_client, environ)

        self.assertEqual(result["POSTGRES_DB"], "research_archive")

    def test_dbname_takes_precedence_over_database_alias(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps(
                {
                    "host": "db.internal",
                    "port": 5432,
                    "dbname": "correct_db",
                    "database": "wrong_db",
                    "username": "app_user",
                    "password": "s3cr3t",
                }
            )
        }
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        result = ecs.resolve_postgres_credentials(secrets_client, environ)

        self.assertEqual(result["POSTGRES_DB"], "correct_db")

    def test_host_port_dbname_fall_back_to_non_secret_env_vars_when_absent(
        self,
    ) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps(
                {"username": "app_user", "password": "s3cr3t"}
            )
        }
        environ = {
            "POSTGRES_SECRET_ID": "arn:...:postgres",
            "POSTGRES_HOST": "db.internal",
            "POSTGRES_PORT": "5432",
            "POSTGRES_DB": "research_archive",
        }

        result = ecs.resolve_postgres_credentials(secrets_client, environ)

        self.assertEqual(result["POSTGRES_HOST"], "db.internal")
        self.assertEqual(result["POSTGRES_PORT"], "5432")
        self.assertEqual(result["POSTGRES_DB"], "research_archive")

    def test_secret_field_takes_precedence_over_env_var_when_both_present(
        self,
    ) -> None:
        secrets_client = self._full_secret(host="from-secret.internal")
        environ = {
            "POSTGRES_SECRET_ID": "arn:...:postgres",
            "POSTGRES_HOST": "from-env-should-be-ignored.internal",
        }

        result = ecs.resolve_postgres_credentials(secrets_client, environ)

        self.assertEqual(result["POSTGRES_HOST"], "from-secret.internal")

    def test_raises_when_host_absent_from_both_secret_and_env(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps(
                {"username": "app_user", "password": "s3cr3t"}
            )
        }
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        with self.assertRaises(ConfigurationError):
            ecs.resolve_postgres_credentials(secrets_client, environ)

    def test_raises_when_secret_id_env_var_is_not_set(self) -> None:
        secrets_client = MagicMock()

        with self.assertRaises(ConfigurationError):
            ecs.resolve_postgres_credentials(secrets_client, {})

        secrets_client.get_secret_value.assert_not_called()

    def test_rejects_localhost_host(self) -> None:
        secrets_client = self._full_secret(host="localhost")
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        with self.assertRaises(ConfigurationError):
            ecs.resolve_postgres_credentials(secrets_client, environ)

    def test_rejects_127_0_0_1_host(self) -> None:
        secrets_client = self._full_secret(host="127.0.0.1")
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        with self.assertRaises(ConfigurationError):
            ecs.resolve_postgres_credentials(secrets_client, environ)

    def test_rejects_localhost_supplied_via_env_fallback(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps(
                {"username": "app_user", "password": "s3cr3t", "port": 5432}
            )
        }
        environ = {
            "POSTGRES_SECRET_ID": "arn:...:postgres",
            "POSTGRES_HOST": "127.0.0.1",
            "POSTGRES_DB": "research_archive",
        }

        with self.assertRaises(ConfigurationError):
            ecs.resolve_postgres_credentials(secrets_client, environ)

    def test_rejects_port_15432(self) -> None:
        secrets_client = self._full_secret(port=15432)
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        with self.assertRaises(ConfigurationError):
            ecs.resolve_postgres_credentials(secrets_client, environ)

    def test_accepts_a_real_private_rds_host_and_port(self) -> None:
        secrets_client = self._full_secret(
            host="research-archive-platform-dev-postgres.clb9d4mkglfd.us-east-1.rds.amazonaws.com",
            port=5432,
        )
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        result = ecs.resolve_postgres_credentials(secrets_client, environ)

        self.assertNotIn("localhost", result["POSTGRES_HOST"])


class ResolveOracleCredentialsTest(unittest.TestCase):
    def test_reads_all_three_fields_from_the_secret(self) -> None:
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
        environ = {"ORACLE_SECRET_ID": "arn:...:oracle"}

        result = ecs.resolve_oracle_credentials(secrets_client, environ)

        self.assertEqual(result["ORACLE_USER"], "kcuser")
        self.assertEqual(result["ORACLE_PASSWORD"], "hunter2")
        self.assertEqual(result["ORACLE_DSN"], "kuali-oracle.bu.edu:1521/KCPROD")

    def test_environment_fallback_is_never_used_in_ecs_mode(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps(
                {
                    "username": "secret_user",
                    "password": "secret_pass",
                    "dsn": "secret-dsn:1521/KCPROD",
                }
            )
        }
        environ = {
            "ORACLE_SECRET_ID": "arn:...:oracle",
            "ORACLE_USER": "env_user_should_be_ignored",
            "ORACLE_PASSWORD": "env_pass_should_be_ignored",
            "ORACLE_DSN": "env-dsn-should-be-ignored",
        }

        result = ecs.resolve_oracle_credentials(secrets_client, environ)

        self.assertEqual(result["ORACLE_USER"], "secret_user")
        self.assertEqual(result["ORACLE_DSN"], "secret-dsn:1521/KCPROD")

    def test_raises_when_secret_id_env_var_is_not_set(self) -> None:
        secrets_client = MagicMock()

        with self.assertRaises(ConfigurationError):
            ecs.resolve_oracle_credentials(secrets_client, {})

        secrets_client.get_secret_value.assert_not_called()

    def test_raises_when_env_vars_present_but_secret_id_missing(self) -> None:
        # This is the key hardening: plain ORACLE_* env vars must NOT be
        # usable as a fallback in --ecs mode, even when fully populated.
        secrets_client = MagicMock()
        environ = {
            "ORACLE_USER": "kcuser",
            "ORACLE_PASSWORD": "hunter2",
            "ORACLE_DSN": "kuali-oracle.bu.edu:1521/KCPROD",
        }

        with self.assertRaises(ConfigurationError):
            ecs.resolve_oracle_credentials(secrets_client, environ)


class MissingSecretsTest(unittest.TestCase):
    def test_raises_secret_not_found_error(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.side_effect = _client_error(
            "ResourceNotFoundException"
        )
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        with self.assertRaises(ecs.SecretNotFoundError):
            ecs.resolve_postgres_credentials(secrets_client, environ)

    def test_raises_access_denied_error(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.side_effect = _client_error(
            "AccessDeniedException"
        )
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        with self.assertRaises(ecs.SecretAccessDeniedError):
            ecs.resolve_postgres_credentials(secrets_client, environ)

    def test_raises_invalid_json_error(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": "not-json"
        }
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        with self.assertRaises(ecs.SecretInvalidJsonError):
            ecs.resolve_postgres_credentials(secrets_client, environ)

    def test_raises_invalid_json_error_when_json_is_not_an_object(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps(["not", "an", "object"])
        }
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        with self.assertRaises(ecs.SecretInvalidJsonError):
            ecs.resolve_postgres_credentials(secrets_client, environ)

    def test_raises_missing_key_error(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps({"host": "db.internal"})
        }
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        with self.assertRaises(ecs.SecretMissingKeyError) as raised:
            ecs.resolve_postgres_credentials(secrets_client, environ)

        self.assertIn("username", str(raised.exception))

    def test_raises_empty_value_error(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps(
                {
                    "username": "  ",
                    "password": "s3cr3t",
                    "host": "db.internal",
                    "port": 5432,
                    "dbname": "research_archive",
                }
            )
        }
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        with self.assertRaises(ecs.SecretEmptyValueError):
            ecs.resolve_postgres_credentials(secrets_client, environ)

    def test_raises_when_secrets_manager_call_itself_fails(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.side_effect = RuntimeError("network error")
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        with self.assertRaises(ConfigurationError):
            ecs.resolve_postgres_credentials(secrets_client, environ)

    def test_raises_when_secret_has_no_secret_string(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {}
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        with self.assertRaises(ConfigurationError):
            ecs.resolve_postgres_credentials(secrets_client, environ)

    def test_all_secret_errors_are_configuration_errors(self) -> None:
        # Every specific exception type must still be catchable by
        # existing ConfigurationError-based handling.
        self.assertTrue(issubclass(ecs.SecretNotFoundError, ConfigurationError))
        self.assertTrue(issubclass(ecs.SecretAccessDeniedError, ConfigurationError))
        self.assertTrue(issubclass(ecs.SecretInvalidJsonError, ConfigurationError))
        self.assertTrue(issubclass(ecs.SecretMissingKeyError, ConfigurationError))
        self.assertTrue(issubclass(ecs.SecretEmptyValueError, ConfigurationError))


class ConfigureEcsEnvironmentTest(unittest.TestCase):
    def _secret_response(self, payload: dict) -> dict:
        return {"SecretString": json.dumps(payload)}

    def test_writes_resolved_credentials_into_environ_in_place(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.side_effect = [
            self._secret_response(
                {
                    "host": "db.internal",
                    "port": 5432,
                    "dbname": "research_archive",
                    "username": "app",
                    "password": "hunter2",
                }
            ),
            self._secret_response(
                {
                    "username": "kcuser",
                    "password": "hunter2",
                    "dsn": "kuali-oracle.bu.edu:1521/KCPROD",
                }
            ),
        ]
        environ = {
            "POSTGRES_SECRET_ID": "arn:...:postgres",
            "ORACLE_SECRET_ID": "arn:...:oracle",
        }

        ecs.configure_ecs_environment(secrets_client, environ)

        self.assertEqual(environ["POSTGRES_HOST"], "db.internal")
        self.assertEqual(environ["ORACLE_USER"], "kcuser")

    def test_uses_a_single_secrets_manager_client_for_both_secrets(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.side_effect = [
            self._secret_response(
                {
                    "host": "db.internal",
                    "port": 5432,
                    "dbname": "research_archive",
                    "username": "app",
                    "password": "hunter2",
                }
            ),
            self._secret_response(
                {
                    "username": "kcuser",
                    "password": "hunter2",
                    "dsn": "kuali-oracle.bu.edu:1521/KCPROD",
                }
            ),
        ]
        environ = {
            "POSTGRES_SECRET_ID": "arn:...:postgres",
            "ORACLE_SECRET_ID": "arn:...:oracle",
        }

        ecs.configure_ecs_environment(secrets_client, environ)

        self.assertEqual(secrets_client.get_secret_value.call_count, 2)

    def test_include_oracle_false_skips_the_oracle_secret_entirely(self) -> None:
        # --migrate-only never touches Oracle.
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = self._secret_response(
            {
                "host": "db.internal",
                "port": 5432,
                "dbname": "research_archive",
                "username": "app",
                "password": "hunter2",
            }
        )
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        ecs.configure_ecs_environment(
            secrets_client, environ, include_oracle=False
        )

        secrets_client.get_secret_value.assert_called_once_with(
            SecretId="arn:...:postgres"
        )
        self.assertNotIn("ORACLE_USER", environ)

    def test_raises_cleanly_when_postgres_credentials_unavailable(self) -> None:
        secrets_client = MagicMock()
        environ: dict[str, str] = {}

        with self.assertRaises(ConfigurationError):
            ecs.configure_ecs_environment(secrets_client, environ)


class SecretRedactionTest(unittest.TestCase):
    """Proves secret values never leak into logs or exceptions."""

    DISTINCTIVE_PASSWORD = "hunter2-VERY-DISTINCTIVE-SECRET-VALUE"
    DISTINCTIVE_DSN = "kuali-oracle.bu.edu:1521/VERY-DISTINCTIVE-DSN"

    def _capture_all_logger_args(self) -> tuple[MagicMock, list[str]]:
        seen: list[str] = []

        def _record(_message, *args, **kwargs):
            seen.extend(str(a) for a in args)
            seen.extend(str(v) for v in kwargs.values())
            return MagicMock()

        bound_logger = MagicMock()
        bound_logger.info.side_effect = _record
        bound_logger.error.side_effect = _record
        parent_logger = MagicMock()
        parent_logger.bind.return_value = bound_logger
        return parent_logger, seen

    def test_password_never_appears_in_log_calls_for_postgres(self) -> None:
        parent_logger, seen = self._capture_all_logger_args()
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps(
                {
                    "host": "db.internal",
                    "port": 5432,
                    "dbname": "research_archive",
                    "username": "app_user",
                    "password": self.DISTINCTIVE_PASSWORD,
                }
            )
        }
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        with patch.object(ecs, "logger", parent_logger):
            ecs.resolve_postgres_credentials(secrets_client, environ)

        combined = " ".join(seen)
        self.assertNotIn(self.DISTINCTIVE_PASSWORD, combined)

    def test_dsn_never_appears_in_log_calls_for_oracle(self) -> None:
        parent_logger, seen = self._capture_all_logger_args()
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps(
                {
                    "username": "kcuser",
                    "password": "hunter2",
                    "dsn": self.DISTINCTIVE_DSN,
                }
            )
        }
        environ = {"ORACLE_SECRET_ID": "arn:...:oracle"}

        with patch.object(ecs, "logger", parent_logger):
            ecs.resolve_oracle_credentials(secrets_client, environ)

        combined = " ".join(seen)
        self.assertNotIn(self.DISTINCTIVE_DSN, combined)

    def test_password_never_appears_in_exception_message_on_failure(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": json.dumps(
                {
                    "host": "db.internal",
                    "port": 5432,
                    "dbname": "research_archive",
                    "username": "app_user",
                    # password deliberately empty -> SecretEmptyValueError
                    "password": "",
                }
            )
        }
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        with self.assertRaises(ecs.SecretEmptyValueError) as raised:
            ecs.resolve_postgres_credentials(secrets_client, environ)

        self.assertNotIn(self.DISTINCTIVE_PASSWORD, str(raised.exception))

    def test_secret_json_content_never_appears_in_exception_text(self) -> None:
        secrets_client = MagicMock()
        secrets_client.get_secret_value.return_value = {
            "SecretString": "not-json-" + self.DISTINCTIVE_PASSWORD
        }
        environ = {"POSTGRES_SECRET_ID": "arn:...:postgres"}

        with self.assertRaises(ecs.SecretInvalidJsonError) as raised:
            ecs.resolve_postgres_credentials(secrets_client, environ)

        self.assertNotIn(self.DISTINCTIVE_PASSWORD, str(raised.exception))


if __name__ == "__main__":
    unittest.main()
