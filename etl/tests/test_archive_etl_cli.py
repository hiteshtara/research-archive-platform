from __future__ import annotations

import sys
from unittest.mock import MagicMock, patch

import pytest

from archive_etl.__main__ import _run_check, _run_domain, build_parser, main


def test_build_parser_requires_a_command() -> None:
    with pytest.raises(SystemExit):
        build_parser().parse_args([])


@pytest.mark.parametrize(
    "domain", ["award", "negotiation", "subaward", "proposal"]
)
def test_build_parser_accepts_each_domain_with_defaults(domain: str) -> None:
    args = build_parser().parse_args([domain])

    assert args.command == domain
    assert args.source is None
    assert args.csv_dir is None
    assert args.limit is None


def test_build_parser_accepts_check_with_no_extra_arguments() -> None:
    args = build_parser().parse_args(["check"])

    assert args.command == "check"


def test_build_parser_rejects_an_unknown_source_value() -> None:
    with pytest.raises(SystemExit):
        build_parser().parse_args(["award", "--source", "parquet"])


def test_run_domain_forwards_source_oracle_as_the_oracle_flag() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(["award", "--source", "oracle"])

    original_argv = list(sys.argv)
    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ) as import_module:
        result = _run_domain("award", args)

    import_module.assert_called_once_with("load_awards_from_csv")
    fake_module.main.assert_called_once_with()
    assert captured_argv == ["load_awards_from_csv.py", "--oracle"]
    assert sys.argv == original_argv
    assert result == 0


def test_run_domain_forwards_source_csv_dir_and_limit() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(
        [
            "subaward",
            "--source",
            "csv",
            "--csv-dir",
            "/tmp/exports",
            "--limit",
            "10",
        ]
    )

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ):
        _run_domain("subaward", args)

    assert captured_argv == [
        "load_subawards_from_csv.py",
        "--csv",
        "--csv-dir",
        "/tmp/exports",
        "--limit",
        "10",
    ]


def test_run_domain_restores_sys_argv_even_if_main_raises() -> None:
    fake_module = MagicMock()
    fake_module.main.side_effect = RuntimeError("boom")
    args = build_parser().parse_args(["proposal"])
    original_argv = list(sys.argv)

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ):
        with pytest.raises(RuntimeError):
            _run_domain("proposal", args)

    assert sys.argv == original_argv


def test_run_check_returns_zero_when_both_checks_pass() -> None:
    with (
        patch("scripts.test_oracle_connection.main", return_value=0),
        patch("scripts.test_postgres_connection.main", return_value=0),
    ):
        assert _run_check() == 0


@pytest.mark.parametrize(
    "oracle_result,postgres_result", [(1, 0), (0, 1), (1, 1)]
)
def test_run_check_returns_one_if_either_check_fails(
    oracle_result: int, postgres_result: int
) -> None:
    with (
        patch("scripts.test_oracle_connection.main", return_value=oracle_result),
        patch(
            "scripts.test_postgres_connection.main",
            return_value=postgres_result,
        ),
    ):
        assert _run_check() == 1


def test_main_dispatches_check() -> None:
    with patch("archive_etl.__main__._run_check", return_value=0) as run_check:
        result = main(["check"])

    run_check.assert_called_once_with()
    assert result == 0


def test_main_dispatches_a_domain() -> None:
    with patch(
        "archive_etl.__main__._run_domain", return_value=0
    ) as run_domain:
        result = main(["award", "--limit", "5"])

    assert run_domain.call_count == 1
    called_domain, called_args = run_domain.call_args[0]
    assert called_domain == "award"
    assert called_args.limit == 5
    assert result == 0
