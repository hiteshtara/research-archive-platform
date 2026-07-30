"""Best-effort redaction of secret-shaped text before it is persisted or logged.

This is defense-in-depth, not a substitute for never logging credentials in
the first place: the pipeline never interpolates known credentials into
messages, but a lower-level driver exception (a malformed DSN, an
authentication failure) could in principle embed connection details in its
own message text. Unlike archive_etl.attachments.models.safe_error_message
(which discards the entire message for arbitrary third-party exceptions),
this keeps the message intact for operational diagnosis and only strips
patterns that look like a secret.
"""

from __future__ import annotations

import re

# key=value / key: value style secrets (password=..., token: ..., etc.)
_KEY_VALUE_SECRET = re.compile(
    r"(?i)\b(password|passwd|pwd|secret|token|api[_-]?key)\s*[:=]\s*\S+"
)

# userinfo embedded in a URL, e.g. postgresql://user:pass@host
_URL_USERINFO = re.compile(r"(?i)(\w+://)[^/@\s]+:[^/@\s]+@")

_MAX_LENGTH = 4000


def redact_error_message(error: BaseException | str) -> str:
    """Return a truncated, secret-redacted string safe to log or persist."""

    message = str(error)
    message = _KEY_VALUE_SECRET.sub(r"\1=[REDACTED]", message)
    message = _URL_USERINFO.sub(r"\1[REDACTED]@", message)
    return message[:_MAX_LENGTH]
