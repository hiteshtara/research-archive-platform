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
echo "STUB: aws $* intentionally not implemented beyond describe-task-definition" >&2
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


class ImageUriSkipsBuildAndPushTest(unittest.TestCase):
    def _run(self, *, extra_args: list[str]) -> subprocess.CompletedProcess:
        with TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            stub_bin = tmp_path / "bin"
            stub_bin.mkdir()
            _make_executable(stub_bin / "aws", AWS_STUB)
            _make_executable(stub_bin / "docker", DOCKER_STUB)

            docker_marker = tmp_path / "docker-invoked.log"

            env = dict(os.environ)
            env["PATH"] = f"{stub_bin}:{env['PATH']}"
            env["DOCKER_STUB_MARKER"] = str(docker_marker)
            env["TASK_FAMILY"] = STUB_FAMILY
            env["SUBNET_IDS"] = "subnet-aaaa,subnet-bbbb"
            env["SECURITY_GROUP_ID"] = "sg-cccc"
            env["POSTGRES_SECRET_ID"] = (
                "arn:aws:secretsmanager:us-east-1:770203350335:secret:test/postgres"
            )

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


if __name__ == "__main__":
    unittest.main()
