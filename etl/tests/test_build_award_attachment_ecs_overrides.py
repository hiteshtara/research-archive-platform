from __future__ import annotations

import unittest

from scripts.build_award_attachment_ecs_overrides import (
    build_container_command,
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
            ]
        )

        self.assertEqual(args.file_id, 9001)
        self.assertEqual(args.limit, 10)
        self.assertTrue(args.retry_failed)
        self.assertTrue(args.dry_run)
        self.assertTrue(args.upload)
        self.assertEqual(args.bucket, "my-bucket")
        self.assertEqual(args.prefix, "custom/prefix")

    def test_defaults_when_nothing_given(self) -> None:
        args = parse_args([])

        self.assertIsNone(args.file_id)
        self.assertIsNone(args.limit)
        self.assertFalse(args.retry_failed)
        self.assertFalse(args.dry_run)
        self.assertFalse(args.upload)
        self.assertIsNone(args.bucket)
        self.assertIsNone(args.prefix)


if __name__ == "__main__":
    unittest.main()
