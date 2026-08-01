"""Exercises scripts/run-award-attachment-loader.sh itself (not just the
Python transform it calls) against stubbed `docker`/`aws` executables -
no real Docker daemon or AWS credentials are ever touched. Proves
--image-uri actually skips the build/login/push path, using the real
shell script and the real transform_loader_task_definition.py, not a
reimplementation of the script's logic."""

from __future__ import annotations

import os
import stat
import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = REPO_ROOT / "scripts" / "run-award-attachment-loader.sh"

STUB_FAMILY = "test-project-test-loader"

AWS_STUB = f"""#!/usr/bin/env bash
set -euo pipefail
if [[ "${{1:-}}" == "ecs" && "${{2:-}}" == "describe-task-definition" ]]; then
  cat <<'JSON'
{{
  "family": "{STUB_FAMILY}",
  "networkMode": "awsvpc",
  "containerDefinitions": [
    {{
      "name": "loader",
      "image": "770203350335.dkr.ecr.us-east-1.amazonaws.com/loader:old-tag"
    }}
  ]
}}
JSON
  exit 0
fi
if [[ "${{1:-}}" == "ecs" && "${{2:-}}" == "register-task-definition" ]]; then
  echo "arn:aws:ecs:us-east-1:770203350335:task-definition/{STUB_FAMILY}:99"
  exit 0
fi
echo "STUB: aws $* intentionally not implemented beyond describe/register-task-definition" >&2
exit 7
"""

DOCKER_STUB = """#!/usr/bin/env bash
set -euo pipefail
: "${DOCKER_STUB_MARKER:?}"
echo "$@" >> "$DOCKER_STUB_MARKER"
echo "STUB: docker should never be invoked when --image-uri is given" >&2
exit 9
"""


def _make_executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)


def _run_script(
    extra_args: list[str], *, extra_env: dict[str, str] | None = None
) -> subprocess.CompletedProcess:
    """Run the real script against stubbed docker/aws executables - shared
    by every test class in this file so each one doesn't reimplement the
    same stub-environment setup."""
    with TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        stub_bin = tmp_path / "bin"
        stub_bin.mkdir()
        _make_executable(stub_bin / "aws", AWS_STUB)
        _make_executable(stub_bin / "docker", DOCKER_STUB)

        docker_marker = tmp_path / "docker-invoked.log"

        env = dict(os.environ)
        # Deliberately unset even if the invoking shell happens to have it
        # exported (e.g. a prior ECS deployment session) - otherwise
        # test_without_image_uri_requires_ecr_repository_uri below would
        # spuriously pass through instead of exercising the "unset"
        # precondition it's named for.
        env.pop("ECR_REPOSITORY_URI", None)
        env.pop("ORACLE_SECRET_ID", None)
        env["PATH"] = f"{stub_bin}:{env['PATH']}"
        env["DOCKER_STUB_MARKER"] = str(docker_marker)
        env["TASK_FAMILY"] = STUB_FAMILY
        env["SUBNET_IDS"] = "subnet-aaaa,subnet-bbbb"
        env["SECURITY_GROUP_ID"] = "sg-cccc"
        env["POSTGRES_SECRET_ID"] = (
            "arn:aws:secretsmanager:us-east-1:770203350335:secret:test/postgres"
        )
        if extra_env:
            env.update(extra_env)

        result = subprocess.run(
            ["bash", str(SCRIPT_PATH), *extra_args],
            cwd=REPO_ROOT,
            env=env,
            capture_output=True,
            text=True,
            timeout=60,
        )
        result.docker_invoked = docker_marker.exists()  # type: ignore[attr-defined]
        return result


class ImageUriSkipsBuildAndPushTest(unittest.TestCase):
    def _run(self, *, extra_args: list[str]) -> subprocess.CompletedProcess:
        return _run_script(extra_args)

    def test_image_uri_skips_docker_entirely(self) -> None:
        result = self._run(
            extra_args=[
                "--migrate-only",
                "--image-uri",
                "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader:20260731T005343Z-b0d475d",
            ]
        )

        self.assertFalse(
            result.docker_invoked,  # type: ignore[attr-defined]
            f"docker was invoked; stdout={result.stdout!r} stderr={result.stderr!r}",
        )

    def test_image_uri_path_reaches_task_definition_registration(self) -> None:
        # The stub `aws` only implements describe-task-definition and fails
        # (exit 7) on anything else - reaching that failure proves the
        # script got all the way through image resolution and the
        # transform step without ever needing docker or ECR.
        result = self._run(
            extra_args=[
                "--migrate-only",
                "--image-uri",
                "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader:20260731T005343Z-b0d475d",
            ]
        )

        self.assertIn("Reusing already-pushed image", result.stdout)
        self.assertNotIn("Building loader image", result.stdout)
        self.assertEqual(result.returncode, 7)

    def test_without_image_uri_requires_ecr_repository_uri(self) -> None:
        # Sanity check on the other branch: with no --image-uri and no
        # ECR_REPOSITORY_URI, the script must fail fast on the missing
        # required variable rather than silently proceeding.
        result = self._run(extra_args=["--migrate-only"])

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ECR_REPOSITORY_URI", result.stderr)


IMAGE_URI_ARGS = [
    "--image-uri",
    "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader:20260731T005343Z-b0d475d",
]


class BatchFlagsForwardedTest(unittest.TestCase):
    """--show-batch needs no Oracle secret (PostgreSQL-only, like
    --migrate-only/--show-upload-status) and reaches the same
    describe-task-definition stub the other read-only modes do - proving
    the batch flags flow all the way through to the generated override
    command, using the real script and the real override-builder module,
    not a reimplementation."""

    def test_show_batch_is_forwarded_and_exempt_from_oracle_secret(self) -> None:
        result = _run_script(["--show-batch", "5", *IMAGE_URI_ARGS])

        self.assertIn('"--show-batch"', result.stdout)
        self.assertIn('"5"', result.stdout)
        self.assertEqual(result.returncode, 7)

    def test_create_batch_and_include_already_uploaded_are_forwarded(self) -> None:
        result = _run_script(
            [
                "--create-batch",
                "10",
                "--include-already-uploaded",
                *IMAGE_URI_ARGS,
            ],
            extra_env={
                "ORACLE_SECRET_ID": (
                    "arn:aws:secretsmanager:us-east-1:770203350335:secret:test/oracle"
                )
            },
        )

        self.assertIn('"--create-batch"', result.stdout)
        self.assertIn('"10"', result.stdout)
        self.assertIn('"--include-already-uploaded"', result.stdout)
        self.assertEqual(result.returncode, 7)

    def test_load_batch_is_forwarded(self) -> None:
        result = _run_script(
            ["--load-batch", "5", *IMAGE_URI_ARGS],
            extra_env={
                "ORACLE_SECRET_ID": (
                    "arn:aws:secretsmanager:us-east-1:770203350335:secret:test/oracle"
                )
            },
        )

        self.assertIn('"--load-batch"', result.stdout)
        self.assertIn('"5"', result.stdout)
        self.assertEqual(result.returncode, 7)

    def test_upload_batch_id_is_forwarded(self) -> None:
        result = _run_script(
            [
                "--upload",
                "--batch-id",
                "5",
                "--bucket",
                "my-bucket",
                *IMAGE_URI_ARGS,
            ],
            extra_env={
                "ORACLE_SECRET_ID": (
                    "arn:aws:secretsmanager:us-east-1:770203350335:secret:test/oracle"
                )
            },
        )

        self.assertIn('"--upload"', result.stdout)
        self.assertIn('"--batch-id"', result.stdout)
        self.assertIn('"5"', result.stdout)
        self.assertEqual(result.returncode, 7)

    def test_create_batch_requires_oracle_secret_id(self) -> None:
        # Unlike --show-batch, --create-batch reads Oracle and so is NOT
        # exempt from the Oracle secret requirement.
        result = _run_script(["--create-batch", "10", *IMAGE_URI_ARGS])

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ORACLE_SECRET_ID", result.stderr)


class BatchFlagsInvalidCombinationsTest(unittest.TestCase):
    """Every rejection here must happen before docker/aws is ever
    touched - a bad combination should fail in milliseconds, not after an
    image build or a task-definition round trip."""

    def test_create_batch_with_upload_is_rejected(self) -> None:
        result = _run_script(["--create-batch", "10", "--upload", *IMAGE_URI_ARGS])

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--create-batch", result.stderr)
        self.assertIn("--upload", result.stderr)
        self.assertFalse(result.docker_invoked)  # type: ignore[attr-defined]

    def test_load_batch_with_file_id_is_rejected(self) -> None:
        result = _run_script(
            ["--load-batch", "5", "--file-id", "9001", *IMAGE_URI_ARGS]
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--load-batch", result.stderr)
        self.assertIn("--file-id", result.stderr)

    def test_show_batch_with_upload_is_rejected(self) -> None:
        result = _run_script(["--show-batch", "5", "--upload", *IMAGE_URI_ARGS])

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--show-batch", result.stderr)

    def test_batch_id_without_upload_is_rejected(self) -> None:
        result = _run_script(["--batch-id", "5", *IMAGE_URI_ARGS])

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--batch-id", result.stderr)
        self.assertIn("--upload", result.stderr)

    def test_batch_id_with_load_file_id_is_rejected(self) -> None:
        result = _run_script(
            [
                "--upload",
                "--batch-id",
                "5",
                "--load-file-id",
                "1",
                *IMAGE_URI_ARGS,
            ]
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--batch-id", result.stderr)
        self.assertIn("--load-file-id", result.stderr)

    def test_nonpositive_create_batch_size_is_rejected(self) -> None:
        result = _run_script(["--create-batch", "0", *IMAGE_URI_ARGS])

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--create-batch", result.stderr)
        self.assertIn("positive", result.stderr)

    def test_multiple_batch_verbs_are_rejected(self) -> None:
        result = _run_script(
            ["--create-batch", "10", "--show-batch", "5", *IMAGE_URI_ARGS]
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--create-batch", result.stderr)
        self.assertIn("--show-batch", result.stderr)


if __name__ == "__main__":
    unittest.main()
