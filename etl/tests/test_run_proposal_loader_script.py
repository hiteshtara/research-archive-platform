"""Exercises scripts/run-proposal-loader.sh itself (not just the Python
transform it calls) against stubbed `docker`/`aws` executables - no real
Docker daemon or AWS credentials are ever touched. Mirrors
test_run_award_attachment_loader_script.py's exact pattern. Proves
--migrate-only produces exactly the required command override and is
rejected when combined with any other verb, using the real shell script
and the real transform_loader_task_definition.py, not a
reimplementation of the script's logic."""

from __future__ import annotations

import json
import os
import re
import stat
import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = REPO_ROOT / "scripts" / "run-proposal-loader.sh"

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

IMAGE_URI_ARGS = [
    "--image-uri",
    "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader:20260814T150157Z-4f00e87",
]


def _make_executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)


def _run_script(
    extra_args: list[str], *, extra_env: dict[str, str] | None = None
) -> subprocess.CompletedProcess:
    with TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        stub_bin = tmp_path / "bin"
        stub_bin.mkdir()
        _make_executable(stub_bin / "aws", AWS_STUB)
        _make_executable(stub_bin / "docker", DOCKER_STUB)

        docker_marker = tmp_path / "docker-invoked.log"

        env = dict(os.environ)
        env.pop("ECR_REPOSITORY_URI", None)
        env["PATH"] = f"{stub_bin}:{env['PATH']}"
        env["DOCKER_STUB_MARKER"] = str(docker_marker)
        env["TASK_FAMILY"] = STUB_FAMILY
        env["SUBNET_IDS"] = "subnet-aaaa,subnet-bbbb"
        env["SECURITY_GROUP_ID"] = "sg-cccc"
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


def _extract_command(stdout: str) -> list[str]:
    """Pulls the real containerOverrides command array out of the
    script's own "Overrides: {...}" log line - parses the actual JSON
    the script builds rather than pattern-matching its formatting."""
    match = re.search(r"Overrides: (\{.*\})", stdout, re.DOTALL)
    assert match, f"no Overrides JSON found in stdout: {stdout!r}"
    overrides = json.loads(match.group(1))
    return overrides["containerOverrides"][0]["command"]


class MigrateOnlyWrapperTest(unittest.TestCase):
    def test_migrate_only_produces_exactly_the_required_command(self) -> None:
        result = _run_script(["--migrate-only", *IMAGE_URI_ARGS])

        self.assertEqual(
            _extract_command(result.stdout),
            ["python3", "load_proposals_from_csv.py", "--ecs", "--migrate-only"],
        )
        # Reaching aws's unimplemented-command stub (exit 7) proves the
        # script got all the way through building the override.
        self.assertEqual(result.returncode, 7)

    def test_migrate_only_skips_docker_when_image_uri_given(self) -> None:
        result = _run_script(["--migrate-only", *IMAGE_URI_ARGS])

        self.assertFalse(
            result.docker_invoked,  # type: ignore[attr-defined]
            f"docker was invoked; stdout={result.stdout!r} stderr={result.stderr!r}",
        )
        self.assertIn("Reusing already-pushed image", result.stdout)
        self.assertNotIn("Building Proposal loader image", result.stdout)

    def test_migrate_only_without_image_uri_requires_ecr_repository_uri(self) -> None:
        result = _run_script(["--migrate-only"])

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ECR_REPOSITORY_URI", result.stderr)

    def test_no_verb_is_still_rejected(self) -> None:
        result = _run_script(IMAGE_URI_ARGS)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--migrate-only", result.stderr)
        self.assertFalse(result.docker_invoked)  # type: ignore[attr-defined]


class MigrateOnlyInvalidCombinationsTest(unittest.TestCase):
    """Every rejection here must happen before docker/aws is ever
    touched - a bad combination should fail in milliseconds, not after a
    task-definition round trip."""

    def test_migrate_only_with_create_batch_is_rejected(self) -> None:
        result = _run_script(
            ["--migrate-only", "--create-batch", "10", *IMAGE_URI_ARGS]
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--migrate-only", result.stderr)
        self.assertIn("--create-batch", result.stderr)
        self.assertFalse(result.docker_invoked)  # type: ignore[attr-defined]

    def test_migrate_only_with_load_batch_is_rejected(self) -> None:
        result = _run_script(
            ["--migrate-only", "--load-batch", "216", *IMAGE_URI_ARGS]
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--migrate-only", result.stderr)
        self.assertIn("--load-batch", result.stderr)

    def test_migrate_only_with_show_batch_is_rejected(self) -> None:
        result = _run_script(
            ["--migrate-only", "--show-batch", "216", *IMAGE_URI_ARGS]
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--migrate-only", result.stderr)
        self.assertIn("--show-batch", result.stderr)

    def test_migrate_only_with_load_proposal_number_is_rejected(self) -> None:
        result = _run_script(
            ["--migrate-only", "--load-proposal-number", "205", *IMAGE_URI_ARGS]
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--migrate-only", result.stderr)

    def test_migrate_only_with_dry_run_is_rejected(self) -> None:
        result = _run_script(["--migrate-only", "--dry-run", *IMAGE_URI_ARGS])

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--migrate-only", result.stderr)
        self.assertIn("--dry-run", result.stderr)


class ExistingVerbsStillWorkTest(unittest.TestCase):
    """Regression coverage: adding --migrate-only must not change any
    existing verb's own forwarded command."""

    def test_show_batch_is_still_forwarded_unchanged(self) -> None:
        result = _run_script(["--show-batch", "216", *IMAGE_URI_ARGS])

        self.assertIn('"--show-batch"', result.stdout)
        self.assertIn('"216"', result.stdout)
        self.assertNotIn('"--migrate-only"', result.stdout)
        self.assertEqual(result.returncode, 7)

    def test_load_batch_is_still_forwarded_unchanged(self) -> None:
        result = _run_script(["--load-batch", "216", *IMAGE_URI_ARGS])

        self.assertIn('"--load-batch"', result.stdout)
        self.assertIn('"216"', result.stdout)
        self.assertNotIn('"--migrate-only"', result.stdout)
        self.assertEqual(result.returncode, 7)


if __name__ == "__main__":
    unittest.main()
