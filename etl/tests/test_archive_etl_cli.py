from __future__ import annotations

import sys
from unittest.mock import MagicMock, patch

import pytest

from archive_etl.__main__ import _run_check, _run_domain, build_parser, main


def test_build_parser_requires_a_command() -> None:
    with pytest.raises(SystemExit):
        build_parser().parse_args([])


@pytest.mark.parametrize(
    "domain",
    [
        "award",
        "negotiation",
        "subaward",
        "proposal",
        "award-attachment",
        "protocol",
    ],
)
def test_build_parser_accepts_each_domain_with_defaults(domain: str) -> None:
    args = build_parser().parse_args([domain])

    assert args.command == domain
    assert args.limit is None


def test_build_parser_accepts_award_attachment_dry_run() -> None:
    args = build_parser().parse_args(["award-attachment", "--dry-run"])

    assert args.command == "award-attachment"
    assert args.dry_run is True


def test_build_parser_rejects_dry_run_for_other_domains() -> None:
    with pytest.raises(SystemExit):
        build_parser().parse_args(["negotiation", "--dry-run"])


def test_build_parser_accepts_award_attachment_ecs_migrate_only() -> None:
    args = build_parser().parse_args(
        ["award-attachment", "--ecs", "--migrate-only"]
    )

    assert args.command == "award-attachment"
    assert args.ecs is True
    assert args.migrate_only is True


def test_build_parser_rejects_ecs_for_other_domains() -> None:
    with pytest.raises(SystemExit):
        build_parser().parse_args(["negotiation", "--ecs"])


def test_build_parser_accepts_award_dry_run() -> None:
    args = build_parser().parse_args(["award", "--dry-run"])

    assert args.command == "award"
    assert args.dry_run is True


def test_build_parser_accepts_award_load_award_id() -> None:
    args = build_parser().parse_args(["award", "--load-award-id", "42"])

    assert args.command == "award"
    assert args.load_award_id == 42


def test_build_parser_accepts_award_create_batch() -> None:
    args = build_parser().parse_args(["award", "--create-batch", "10"])

    assert args.create_batch == 10


def test_build_parser_accepts_award_load_batch() -> None:
    args = build_parser().parse_args(["award", "--load-batch", "5"])

    assert args.load_batch == 5


def test_build_parser_accepts_award_show_batch() -> None:
    args = build_parser().parse_args(["award", "--show-batch", "5"])

    assert args.show_batch == 5


def test_build_parser_accepts_award_ecs_migrate_only() -> None:
    args = build_parser().parse_args(["award", "--ecs", "--migrate-only"])

    assert args.command == "award"
    assert args.ecs is True
    assert args.migrate_only is True


def test_build_parser_rejects_load_award_id_for_award_attachment() -> None:
    with pytest.raises(SystemExit):
        build_parser().parse_args(["award-attachment", "--load-award-id", "1"])


def test_run_domain_forwards_award_dry_run() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(["award", "--dry-run"])

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ) as import_module:
        _run_domain("award", args)

    import_module.assert_called_once_with("load_awards_from_csv")
    assert captured_argv == ["load_awards_from_csv.py", "--dry-run"]


def test_run_domain_forwards_award_load_award_id() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(
        ["award", "--load-award-id", "42", "--dry-run"]
    )

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ) as import_module:
        _run_domain("award", args)

    import_module.assert_called_once_with("load_awards_from_csv")
    assert captured_argv == [
        "load_awards_from_csv.py",
        "--dry-run",
        "--load-award-id",
        "42",
    ]


def test_run_domain_forwards_award_create_batch() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(["award", "--create-batch", "10"])

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ) as import_module:
        _run_domain("award", args)

    import_module.assert_called_once_with("load_awards_from_csv")
    assert captured_argv == ["load_awards_from_csv.py", "--create-batch", "10"]


def test_run_domain_forwards_award_load_batch_with_dry_run() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(
        ["award", "--load-batch", "5", "--dry-run"]
    )

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ) as import_module:
        _run_domain("award", args)

    import_module.assert_called_once_with("load_awards_from_csv")
    assert captured_argv == [
        "load_awards_from_csv.py",
        "--dry-run",
        "--load-batch",
        "5",
    ]


def test_run_domain_forwards_award_show_batch() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(["award", "--show-batch", "5"])

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ) as import_module:
        _run_domain("award", args)

    import_module.assert_called_once_with("load_awards_from_csv")
    assert captured_argv == ["load_awards_from_csv.py", "--show-batch", "5"]


def test_run_domain_forwards_award_ecs_migrate_only() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(["award", "--ecs", "--migrate-only"])

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ) as import_module:
        _run_domain("award", args)

    import_module.assert_called_once_with("load_awards_from_csv")
    assert captured_argv == [
        "load_awards_from_csv.py",
        "--ecs",
        "--migrate-only",
    ]


def test_build_parser_accepts_award_attachment_show_upload_status() -> None:
    args = build_parser().parse_args(
        ["award-attachment", "--ecs", "--show-upload-status", "--file-id", "1"]
    )

    assert args.command == "award-attachment"
    assert args.show_upload_status is True
    assert args.file_id == 1


def test_build_parser_accepts_award_attachment_load_file_id() -> None:
    args = build_parser().parse_args(
        ["award-attachment", "--load-file-id", "1"]
    )

    assert args.command == "award-attachment"
    assert args.load_file_id == 1


def test_build_parser_accepts_check_with_no_extra_arguments() -> None:
    args = build_parser().parse_args(["check"])

    assert args.command == "check"
    assert args.domain is None


def test_build_parser_accepts_check_with_a_domain() -> None:
    args = build_parser().parse_args(["check", "protocol"])

    assert args.command == "check"
    assert args.domain == "protocol"


def test_run_domain_forwards_nothing_when_limit_is_not_given() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(["award"])

    original_argv = list(sys.argv)
    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ) as import_module:
        result = _run_domain("award", args)

    import_module.assert_called_once_with("load_awards_from_csv")
    fake_module.main.assert_called_once_with()
    assert captured_argv == ["load_awards_from_csv.py"]
    assert sys.argv == original_argv
    assert result == 0


def test_run_domain_forwards_limit() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(["subaward", "--limit", "10"])

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ):
        _run_domain("subaward", args)

    assert captured_argv == [
        "load_subawards_from_csv.py",
        "--limit",
        "10",
    ]


def test_run_domain_forwards_dry_run() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(["award-attachment", "--dry-run"])

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ) as import_module:
        _run_domain("award-attachment", args)

    import_module.assert_called_once_with("load_award_attachments")
    assert captured_argv == ["load_award_attachments.py", "--dry-run"]


def test_run_domain_forwards_limit_and_dry_run_together() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(
        ["award-attachment", "--limit", "10", "--dry-run"]
    )

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ):
        _run_domain("award-attachment", args)

    assert captured_argv == [
        "load_award_attachments.py",
        "--limit",
        "10",
        "--dry-run",
    ]


def test_run_domain_forwards_ecs_and_migrate_only() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(
        ["award-attachment", "--ecs", "--migrate-only"]
    )

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ) as import_module:
        _run_domain("award-attachment", args)

    import_module.assert_called_once_with("load_award_attachments")
    assert captured_argv == [
        "load_award_attachments.py",
        "--ecs",
        "--migrate-only",
    ]


def test_run_domain_forwards_show_upload_status_and_file_id() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(
        ["award-attachment", "--ecs", "--show-upload-status", "--file-id", "1"]
    )

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ) as import_module:
        _run_domain("award-attachment", args)

    import_module.assert_called_once_with("load_award_attachments")
    assert captured_argv == [
        "load_award_attachments.py",
        "--file-id",
        "1",
        "--ecs",
        "--show-upload-status",
    ]


def test_build_parser_accepts_award_attachment_create_batch() -> None:
    args = build_parser().parse_args(
        ["award-attachment", "--ecs", "--create-batch", "10"]
    )

    assert args.create_batch == 10


def test_build_parser_accepts_award_attachment_show_batch() -> None:
    args = build_parser().parse_args(["award-attachment", "--show-batch", "5"])

    assert args.show_batch == 5


def test_build_parser_accepts_award_attachment_load_batch() -> None:
    args = build_parser().parse_args(["award-attachment", "--load-batch", "5"])

    assert args.load_batch == 5


def test_build_parser_accepts_award_attachment_upload_batch_id() -> None:
    args = build_parser().parse_args(
        ["award-attachment", "--upload", "--batch-id", "5"]
    )

    assert args.upload is True
    assert args.batch_id == 5


def test_run_domain_forwards_create_batch_and_include_already_uploaded() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(
        [
            "award-attachment",
            "--ecs",
            "--create-batch",
            "10",
            "--include-already-uploaded",
        ]
    )

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ) as import_module:
        _run_domain("award-attachment", args)

    import_module.assert_called_once_with("load_award_attachments")
    assert captured_argv == [
        "load_award_attachments.py",
        "--ecs",
        "--create-batch",
        "10",
        "--include-already-uploaded",
    ]


def test_run_domain_forwards_show_batch() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(["award-attachment", "--show-batch", "5"])

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ):
        _run_domain("award-attachment", args)

    assert captured_argv == ["load_award_attachments.py", "--show-batch", "5"]


def test_run_domain_forwards_load_batch() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(["award-attachment", "--load-batch", "5"])

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ):
        _run_domain("award-attachment", args)

    assert captured_argv == ["load_award_attachments.py", "--load-batch", "5"]


def test_run_domain_forwards_upload_and_batch_id() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(
        ["award-attachment", "--upload", "--batch-id", "5"]
    )

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ):
        _run_domain("award-attachment", args)

    assert captured_argv == [
        "load_award_attachments.py",
        "--upload",
        "--batch-id",
        "5",
    ]


def test_run_domain_forwards_load_file_id() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    args = build_parser().parse_args(
        ["award-attachment", "--load-file-id", "1"]
    )

    with patch(
        "archive_etl.__main__.importlib.import_module",
        return_value=fake_module,
    ) as import_module:
        _run_domain("award-attachment", args)

    import_module.assert_called_once_with("load_award_attachments")
    assert captured_argv == [
        "load_award_attachments.py",
        "--load-file-id",
        "1",
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
        assert _run_check(None) == 0


def test_run_check_with_a_domain_also_runs_a_limited_smoke_test() -> None:
    fake_module = MagicMock()
    captured_argv: list[str] = []
    fake_module.main.side_effect = lambda: captured_argv.extend(sys.argv)

    with (
        patch("scripts.test_oracle_connection.main", return_value=0),
        patch("scripts.test_postgres_connection.main", return_value=0),
        patch(
            "archive_etl.__main__.importlib.import_module",
            return_value=fake_module,
        ) as import_module,
    ):
        assert _run_check("protocol") == 0

    import_module.assert_called_once_with("load_protocols")
    assert captured_argv == ["load_protocols.py", "--limit", "5"]


def test_run_check_with_a_domain_skips_the_smoke_test_if_connectivity_fails() -> None:
    with (
        patch("scripts.test_oracle_connection.main", return_value=1),
        patch("scripts.test_postgres_connection.main", return_value=0),
        patch(
            "archive_etl.__main__.importlib.import_module"
        ) as import_module,
    ):
        assert _run_check("protocol") == 1

    import_module.assert_not_called()


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
        assert _run_check(None) == 1


def test_main_dispatches_check() -> None:
    with patch("archive_etl.__main__._run_check", return_value=0) as run_check:
        result = main(["check"])

    run_check.assert_called_once_with(None)
    assert result == 0


def test_main_dispatches_check_with_a_domain() -> None:
    with patch("archive_etl.__main__._run_check", return_value=0) as run_check:
        result = main(["check", "protocol"])

    run_check.assert_called_once_with("protocol")
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


def test_main_dispatches_explore_to_the_explorer_module() -> None:
    with patch("archive_etl.explorer.main", return_value=0) as explore_main:
        result = main(["explore", "unit", "--unit-number", "1203250000"])

    explore_main.assert_called_once_with(["unit", "--unit-number", "1203250000"])
    assert result == 0


def test_main_explore_never_reaches_the_generic_domain_dispatcher() -> None:
    with (
        patch("archive_etl.explorer.main", return_value=0),
        patch("archive_etl.__main__._run_domain") as run_domain,
    ):
        main(["explore", "award", "--award-number", "100012-00002"])

    run_domain.assert_not_called()
