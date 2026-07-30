from __future__ import annotations

from archive_etl.utils.redaction import redact_error_message


def test_redacts_key_value_password() -> None:
    message = redact_error_message(
        "connection failed: password=hunter2 invalid"
    )

    assert "hunter2" not in message
    assert "password=[REDACTED]" in message


def test_redacts_url_userinfo() -> None:
    message = redact_error_message(
        "could not connect to postgresql://archive_admin:hunter2@db.example.com/archive"
    )

    assert "hunter2" not in message
    assert "postgresql://[REDACTED]@db.example.com/archive" in message


def test_preserves_ordinary_error_text() -> None:
    original = "Ambiguous parent for protocol_number=100004, sequence=3"

    assert redact_error_message(original) == original


def test_truncates_long_messages() -> None:
    message = redact_error_message("x" * 5000)

    assert len(message) == 4000


def test_accepts_exception_instances() -> None:
    message = redact_error_message(RuntimeError("token: abc123 leaked"))

    assert "abc123" not in message
