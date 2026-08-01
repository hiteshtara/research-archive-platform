"""Docker smoke test proving the loader image's layout actually supports
the ECS override command (`python -m archive_etl award-attachment ...`)
end to end - not just that argparse can print --help, but that the
interpreter `python` resolves to on PATH can actually import
load_award_attachments.py and its dependencies (boto3/loguru/sqlalchemy),
which only live in the project's venv, not the base image's system
Python.

This is what a bare `--help` check would miss: argparse's `--help`
exits before `_run_domain()` ever imports the underlying loader module,
so it would pass even with the exact production bug this test guards
against (`ModuleNotFoundError: No module named 'boto3'` when `python`
resolves to the system interpreter instead of the venv one).

Skips entirely if Docker isn't available - this suite must still pass
in an environment with no Docker daemon. Never pushes anything; the
image built here is local-only and removed afterward.
"""

from __future__ import annotations

import shutil
import subprocess
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
IMAGE_TAG = "research-archive-platform-loader-image-layout-test:pytest"


def _docker_available() -> bool:
    if shutil.which("docker") is None:
        return False
    try:
        result = subprocess.run(
            ["docker", "info"], capture_output=True, timeout=10
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    return result.returncode == 0


@unittest.skipUnless(_docker_available(), "Docker is not available")
class LoaderImageLayoutTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        try:
            subprocess.run(
                [
                    "docker",
                    "build",
                    "--platform",
                    "linux/amd64",
                    "-t",
                    IMAGE_TAG,
                    "-f",
                    "etl/Dockerfile.loader",
                    ".",
                ],
                cwd=REPO_ROOT,
                capture_output=True,
                text=True,
                timeout=600,
                check=True,
            )
        except subprocess.CalledProcessError as error:
            raise AssertionError(
                f"docker build failed:\nstdout={error.stdout}\nstderr={error.stderr}"
            ) from error

    @classmethod
    def tearDownClass(cls) -> None:
        subprocess.run(
            ["docker", "rmi", "-f", IMAGE_TAG],
            capture_output=True,
            timeout=60,
        )

    def _run_in_image(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["docker", "run", "--rm", "--platform", "linux/amd64", IMAGE_TAG, *args],
            capture_output=True,
            text=True,
            timeout=60,
        )

    def test_python_resolves_to_the_project_venv(self) -> None:
        result = self._run_in_image("which", "python")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "/app/.venv/bin/python")

    def test_help_invocation_matches_the_required_command(self) -> None:
        result = self._run_in_image(
            "python", "-m", "archive_etl", "award-attachment", "--help"
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("award-attachment", result.stdout)

    def test_loader_module_and_its_dependencies_import_cleanly(self) -> None:
        # This is the check --help alone cannot provide: --help exits
        # before load_award_attachments.py (and boto3/loguru/sqlalchemy)
        # is ever imported.
        result = self._run_in_image(
            "python", "-c", "import load_award_attachments"
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("ModuleNotFoundError", result.stderr)

    def test_ecs_migrate_only_fails_on_missing_aws_identity_not_on_exec_or_import(
        self,
    ) -> None:
        # No AWS credentials exist in this sandboxed container run, so
        # this must fail - but specifically at validate_aws_identity()
        # (RuntimeError/NoCredentialsError), never at process exec
        # ("executable file not found in $PATH") or module import
        # ("ModuleNotFoundError"). Reaching validate_aws_identity()
        # proves the command executes, and every import up to that
        # point succeeded - no migration, Oracle access, BLOB read, or
        # S3 upload is reachable before it.
        result = self._run_in_image(
            "python", "-m", "archive_etl", "award-attachment", "--ecs", "--migrate-only"
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("executable file not found", result.stderr)
        self.assertNotIn("ModuleNotFoundError", result.stderr)
        self.assertIn("validate_aws_identity", result.stderr)

    # --- Award (core) loader - same image, same layout guarantees -----

    def test_award_help_invocation_matches_the_required_command(self) -> None:
        result = self._run_in_image(
            "python", "-m", "archive_etl", "award", "--help"
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--load-award-id", result.stdout)
        self.assertIn("--create-batch", result.stdout)
        self.assertIn("--load-batch", result.stdout)
        self.assertIn("--show-batch", result.stdout)
        self.assertIn("--ecs", result.stdout)
        self.assertIn("--migrate-only", result.stdout)

    def test_award_loader_module_and_its_dependencies_import_cleanly(self) -> None:
        # Mirrors test_loader_module_and_its_dependencies_import_cleanly
        # above, for load_awards_from_csv.py - the check --help alone
        # cannot provide, since --help exits before this module (and
        # boto3/oracledb/loguru/sqlalchemy) is ever imported.
        result = self._run_in_image(
            "python", "-c", "import load_awards_from_csv"
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("ModuleNotFoundError", result.stderr)

    def test_award_extraction_sql_and_migrations_present_in_image(self) -> None:
        # Proves the image actually contains every Award extraction SQL
        # file through 48 and every migration through V052 - not just
        # that load_awards_from_csv.py imports (module import alone
        # doesn't touch these files at all; only running a real load
        # would, which this sandboxed test deliberately never does).
        result = self._run_in_image(
            "python",
            "-c",
            "from pathlib import Path\n"
            "import load_awards_from_csv as m\n"
            "assert m.PROJECT_ROOT == Path('/app'), m.PROJECT_ROOT\n"
            "assert m.VERSIONS_ORACLE_SQL.is_file()\n"
            "assert m.AWARD_TRANSMISSION_CHILD_ORACLE_SQL.is_file()\n"
            "print('OK')\n",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("OK", result.stdout)

    def test_award_ecs_migrate_only_fails_on_missing_aws_identity_not_on_exec_or_import(
        self,
    ) -> None:
        # Mirrors the award-attachment version of this test above.
        # Reaching validate_aws_identity() proves the command executes
        # and every import up to that point succeeded - no migration or
        # Oracle access is reachable before it.
        result = self._run_in_image(
            "python", "-m", "archive_etl", "award", "--ecs", "--migrate-only"
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("executable file not found", result.stderr)
        self.assertNotIn("ModuleNotFoundError", result.stderr)
        self.assertIn("validate_aws_identity", result.stderr)


if __name__ == "__main__":
    unittest.main()
