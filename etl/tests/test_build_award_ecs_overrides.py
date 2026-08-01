from __future__ import annotations

import unittest

from scripts.build_award_ecs_overrides import (
    build_container_command,
    build_environment_overrides,
    build_run_task_overrides,
    parse_args,
)

# Every generated command must start with this exact prefix: a real
# executable (`python`) already on the container's PATH, never a bare
# script filename - see build_container_command's docstring for why.
MODULE_CLI_PREFIX = ["python", "-m", "archive_etl", "award", "--ecs"]


class BuildContainerCommandTest(unittest.TestCase):
    def test_always_uses_the_module_cli_prefix(self) -> None:
        command = build_container_command()

        self.assertEqual(command, MODULE_CLI_PREFIX)

    def test_never_uses_the_bare_script_filename(self) -> None:
        command = build_container_command(
            migrate_only=True, load_award_id=1, dry_run=True
        )

        self.assertNotIn("load_awards_from_csv.py", command)
        self.assertEqual(command[0], "python")

    def test_migrate_only_produces_the_exact_required_command(self) -> None:
        command = build_container_command(migrate_only=True)

        self.assertEqual(command, [*MODULE_CLI_PREFIX, "--migrate-only"])

    def test_load_award_id_produces_the_exact_required_command(self) -> None:
        command = build_container_command(load_award_id=209899)

        self.assertEqual(
            command, [*MODULE_CLI_PREFIX, "--load-award-id", "209899"]
        )

    def test_load_award_id_combines_with_dry_run(self) -> None:
        command = build_container_command(load_award_id=209899, dry_run=True)

        self.assertEqual(
            command,
            [*MODULE_CLI_PREFIX, "--load-award-id", "209899", "--dry-run"],
        )

    def test_create_batch_produces_the_exact_required_command(self) -> None:
        command = build_container_command(create_batch=10)

        self.assertEqual(command, [*MODULE_CLI_PREFIX, "--create-batch", "10"])

    def test_load_batch_dry_run_produces_the_exact_required_command(self) -> None:
        command = build_container_command(load_batch=5, dry_run=True)

        self.assertEqual(
            command, [*MODULE_CLI_PREFIX, "--load-batch", "5", "--dry-run"]
        )

    def test_show_batch_produces_the_exact_required_command(self) -> None:
        command = build_container_command(show_batch=5)

        self.assertEqual(command, [*MODULE_CLI_PREFIX, "--show-batch", "5"])

    def test_defaults_produce_just_the_prefix(self) -> None:
        command = build_container_command()

        self.assertEqual(command, MODULE_CLI_PREFIX)


class BuildEnvironmentOverridesTest(unittest.TestCase):
    def test_includes_only_the_fields_that_were_given(self) -> None:
        environment = build_environment_overrides(
            postgres_secret_id="arn:...:postgres",
            oracle_secret_id="arn:...:oracle",
        )

        names = {entry["name"] for entry in environment}
        self.assertEqual(names, {"POSTGRES_SECRET_ID", "ORACLE_SECRET_ID"})

    def test_supports_all_six_non_secret_variables(self) -> None:
        environment = build_environment_overrides(
            postgres_secret_id="arn:...:postgres",
            oracle_secret_id="arn:...:oracle",
            postgres_host="db.internal",
            postgres_port="5432",
            postgres_db="research_archive",
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
                "aws_region",
            },
        )
        for forbidden in ("password", "dsn", "secret_value", "secret_json"):
            self.assertNotIn(forbidden, parameter_names)


class BuildRunTaskOverridesTest(unittest.TestCase):
    def test_shape_matches_ecs_container_overrides(self) -> None:
        overrides = build_run_task_overrides(load_award_id=209899, dry_run=True)

        self.assertEqual(len(overrides["containerOverrides"]), 1)
        container = overrides["containerOverrides"][0]
        self.assertEqual(container["name"], "loader")
        self.assertEqual(
            container["command"],
            [*MODULE_CLI_PREFIX, "--load-award-id", "209899", "--dry-run"],
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
                "--load-award-id",
                "209899",
                "--dry-run",
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
                "--aws-region",
                "us-east-1",
            ]
        )

        self.assertEqual(args.load_award_id, 209899)
        self.assertTrue(args.dry_run)
        self.assertEqual(args.postgres_secret_id, "arn:...:postgres")
        self.assertEqual(args.oracle_secret_id, "arn:...:oracle")
        self.assertEqual(args.postgres_host, "db.internal")
        self.assertEqual(args.postgres_port, "5432")
        self.assertEqual(args.postgres_db, "research_archive")
        self.assertEqual(args.aws_region, "us-east-1")

    def test_parses_migrate_only(self) -> None:
        args = parse_args(["--migrate-only"])

        self.assertTrue(args.migrate_only)

    def test_parses_create_batch(self) -> None:
        args = parse_args(["--create-batch", "10"])

        self.assertEqual(args.create_batch, 10)

    def test_parses_show_batch_and_load_batch(self) -> None:
        args = parse_args(["--show-batch", "5"])
        self.assertEqual(args.show_batch, 5)

        args = parse_args(["--load-batch", "5"])
        self.assertEqual(args.load_batch, 5)

    def test_has_no_ecs_flag_since_ecs_is_always_included(self) -> None:
        # --ecs is unconditionally baked into build_container_command's
        # output (this loader is only ever invoked this way from
        # scripts/run-award-loader.sh), so there is deliberately no
        # --ecs CLI flag here to accidentally set to False.
        with self.assertRaises(SystemExit):
            parse_args(["--ecs"])

    def test_has_no_award_attachment_only_flags(self) -> None:
        for flag in ("--upload", "--bucket", "--prefix", "--batch-id"):
            with self.assertRaises(SystemExit):
                parse_args([flag])

    def test_defaults_when_nothing_given(self) -> None:
        args = parse_args([])

        self.assertIsNone(args.load_award_id)
        self.assertFalse(args.dry_run)
        self.assertFalse(args.migrate_only)
        self.assertIsNone(args.create_batch)
        self.assertIsNone(args.load_batch)
        self.assertIsNone(args.show_batch)
        self.assertIsNone(args.postgres_secret_id)
        self.assertIsNone(args.oracle_secret_id)


if __name__ == "__main__":
    unittest.main()
