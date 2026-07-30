from __future__ import annotations

import unittest

from scripts.build_award_attachment_ecs_overrides import (
    build_container_command,
    build_environment_overrides,
    build_run_task_overrides,
    parse_args,
)


class BuildContainerCommandTest(unittest.TestCase):
    def test_always_includes_ecs_flag(self) -> None:
        command = build_container_command()

        self.assertEqual(command, ["load_award_attachments.py", "--ecs"])

    def test_dry_run_file_id_lookup(self) -> None:
        command = build_container_command(file_id=9001, dry_run=True)

        self.assertEqual(
            command,
            ["load_award_attachments.py", "--ecs", "--dry-run", "--file-id", "9001"],
        )

    def test_upload_with_limit_and_retry_failed(self) -> None:
        command = build_container_command(
            upload=True, limit=100, retry_failed=True
        )

        self.assertEqual(
            command,
            [
                "load_award_attachments.py",
                "--ecs",
                "--upload",
                "--limit",
                "100",
                "--retry-failed",
            ],
        )

    def test_bucket_and_prefix_are_passed_through(self) -> None:
        command = build_container_command(
            upload=True, bucket="my-bucket", prefix="custom/prefix"
        )

        self.assertIn("--bucket", command)
        self.assertIn("my-bucket", command)
        self.assertIn("--prefix", command)
        self.assertIn("custom/prefix", command)

    def test_omits_flags_that_were_not_requested(self) -> None:
        command = build_container_command(limit=5)

        self.assertNotIn("--upload", command)
        self.assertNotIn("--dry-run", command)
        self.assertNotIn("--file-id", command)
        self.assertNotIn("--retry-failed", command)
        self.assertNotIn("--migrate-only", command)

    def test_migrate_only_produces_the_documented_example_command(self) -> None:
        command = build_container_command(migrate_only=True)

        self.assertEqual(
            command,
            ["load_award_attachments.py", "--ecs", "--migrate-only"],
        )


class BuildEnvironmentOverridesTest(unittest.TestCase):
    def test_includes_only_the_fields_that_were_given(self) -> None:
        environment = build_environment_overrides(
            postgres_secret_id="arn:...:postgres",
            oracle_secret_id="arn:...:oracle",
        )

        names = {entry["name"] for entry in environment}
        self.assertEqual(names, {"POSTGRES_SECRET_ID", "ORACLE_SECRET_ID"})

    def test_supports_all_seven_non_secret_variables(self) -> None:
        environment = build_environment_overrides(
            postgres_secret_id="arn:...:postgres",
            oracle_secret_id="arn:...:oracle",
            postgres_host="db.internal",
            postgres_port="5432",
            postgres_db="research_archive",
            data_bucket_name="research-archive-platform-dev-documents-770203350335",
            aws_region="us-east-1",
        )

        names = {entry["name"] for entry in environment}
        self.assertEqual(
            names,
            {
                "POSTGRES_SECRET_ID",
                "ORACLE_SECRET_ID",
                "POSTGRES_HOST",
                "POSTGRES_PORT",
                "POSTGRES_DB",
                "DATA_BUCKET_NAME",
                "AWS_REGION",
            },
        )

    def test_empty_when_nothing_given(self) -> None:
        self.assertEqual(build_environment_overrides(), [])

    def test_only_ever_accepts_identifiers_never_credentials(self) -> None:
        # This function has no parameter that could carry a password, a
        # DSN, or a secret's JSON content - a purely structural guarantee,
        # verified here by checking the exact accepted parameter names.
        import inspect

        signature = inspect.signature(build_environment_overrides)
        parameter_names = set(signature.parameters.keys())

        self.assertEqual(
            parameter_names,
            {
                "postgres_secret_id",
                "oracle_secret_id",
                "postgres_host",
                "postgres_port",
                "postgres_db",
                "data_bucket_name",
                "aws_region",
            },
        )
        for forbidden in ("password", "dsn", "secret_value", "secret_json"):
            self.assertNotIn(forbidden, parameter_names)


class BuildRunTaskOverridesTest(unittest.TestCase):
    def test_shape_matches_ecs_container_overrides(self) -> None:
        overrides = build_run_task_overrides(file_id=9001, dry_run=True)

        self.assertEqual(len(overrides["containerOverrides"]), 1)
        container = overrides["containerOverrides"][0]
        self.assertEqual(container["name"], "loader")
        self.assertEqual(
            container["command"],
            ["load_award_attachments.py", "--ecs", "--dry-run", "--file-id", "9001"],
        )

    def test_includes_environment_override_when_secret_ids_given(self) -> None:
        overrides = build_run_task_overrides(
            migrate_only=True,
            postgres_secret_id="arn:...:postgres",
        )

        container = overrides["containerOverrides"][0]
        self.assertIn("environment", container)
        names = {entry["name"] for entry in container["environment"]}
        self.assertIn("POSTGRES_SECRET_ID", names)

    def test_omits_environment_key_entirely_when_nothing_to_pass(self) -> None:
        overrides = build_run_task_overrides(dry_run=True)

        container = overrides["containerOverrides"][0]
        self.assertNotIn("environment", container)

    def test_never_includes_a_secret_value_anywhere_in_the_json(self) -> None:
        import json

        overrides = build_run_task_overrides(
            migrate_only=True,
            postgres_secret_id="arn:...:postgres",
            oracle_secret_id="arn:...:oracle",
            postgres_host="db.internal",
        )

        serialized = json.dumps(overrides)
        for forbidden in ("password", "hunter2", "dsn", "secret_string", "SecretString"):
            self.assertNotIn(forbidden, serialized)


class ParseArgsTest(unittest.TestCase):
    def test_parses_all_supported_flags(self) -> None:
        args = parse_args(
            [
                "--file-id",
                "9001",
                "--limit",
                "10",
                "--retry-failed",
                "--dry-run",
                "--upload",
                "--bucket",
                "my-bucket",
                "--prefix",
                "custom/prefix",
                "--postgres-secret-id",
                "arn:...:postgres",
                "--oracle-secret-id",
                "arn:...:oracle",
                "--postgres-host",
                "db.internal",
                "--postgres-port",
                "5432",
                "--postgres-db",
                "research_archive",
                "--data-bucket-name",
                "my-bucket",
                "--aws-region",
                "us-east-1",
            ]
        )

        self.assertEqual(args.file_id, 9001)
        self.assertEqual(args.limit, 10)
        self.assertTrue(args.retry_failed)
        self.assertTrue(args.dry_run)
        self.assertTrue(args.upload)
        self.assertEqual(args.bucket, "my-bucket")
        self.assertEqual(args.prefix, "custom/prefix")
        self.assertEqual(args.postgres_secret_id, "arn:...:postgres")
        self.assertEqual(args.oracle_secret_id, "arn:...:oracle")
        self.assertEqual(args.postgres_host, "db.internal")
        self.assertEqual(args.postgres_port, "5432")
        self.assertEqual(args.postgres_db, "research_archive")
        self.assertEqual(args.data_bucket_name, "my-bucket")
        self.assertEqual(args.aws_region, "us-east-1")

    def test_parses_migrate_only(self) -> None:
        args = parse_args(["--migrate-only"])

        self.assertTrue(args.migrate_only)

    def test_defaults_when_nothing_given(self) -> None:
        args = parse_args([])

        self.assertIsNone(args.file_id)
        self.assertIsNone(args.limit)
        self.assertFalse(args.retry_failed)
        self.assertFalse(args.dry_run)
        self.assertFalse(args.upload)
        self.assertFalse(args.migrate_only)
        self.assertIsNone(args.bucket)
        self.assertIsNone(args.prefix)
        self.assertIsNone(args.postgres_secret_id)
        self.assertIsNone(args.oracle_secret_id)


if __name__ == "__main__":
    unittest.main()
