"""CloudWatch-friendly structured JSON logging for --ecs mode.

Each log line becomes a single JSON object on stdout - one JSON object per
line is exactly what CloudWatch Logs Insights expects to index individual
fields. ECS's `awslogs` log driver (already configured for the loader task
in terraform/modules/ecs/main.tf) forwards container stdout to CloudWatch
Logs verbatim; nothing else needs to change for these lines to become
queryable there.

Fields: timestamp, run_id (bound once for the whole run), level, message,
and whichever of stage/file_id/status/elapsed_ms/secret_id a call site
bound via logger.bind(...). secret_id is a Secrets Manager ARN/name - an
identifier, never a credential - and is the only secret-adjacent field
this schema has room for at all; there is no field for arbitrary secret
content, a DSN, a connection URL, or a resolved username/password value.
Never includes SQL text or BLOB content either - this module cannot
inspect message content to enforce that automatically, so it is a
calling-convention responsibility (see load_award_attachments.py's
per-file logging, which only ever binds/logs identifiers, status labels,
and timings, never query text or bytes, and archive_etl/config/ecs.py,
which only ever binds secret_id, never a resolved credential value) same
as secret redaction remains each caller's job via redact_error_message()
before anything reaches last_error/an exception log.
"""

from __future__ import annotations

import json
import sys
from typing import Any

from loguru import logger

_BOUND_FIELDS = (
    "run_id",
    "stage",
    "file_id",
    "status",
    "elapsed_ms",
    "secret_id",
)


def _json_sink(message: Any) -> None:
    record = message.record
    payload: dict[str, Any] = {
        "timestamp": record["time"].isoformat(),
        "level": record["level"].name,
        "message": record["message"],
    }

    extra = record["extra"]
    for field in _BOUND_FIELDS:
        if field in extra:
            payload[field] = extra[field]

    exception = record["exception"]
    if exception is not None and exception.type is not None:
        payload["exception_type"] = exception.type.__name__

    print(json.dumps(payload, default=str), file=sys.stdout, flush=True)


def configure_structured_logging(run_id: str) -> None:
    """Replace loguru's default (human-readable, stderr) handler with a
    single stdout JSON sink, binding run_id onto every subsequent log
    call for the rest of the process. Call once, at the very start of
    --ecs mode, before any other logging happens."""
    logger.remove()
    logger.configure(extra={"run_id": run_id})
    logger.add(_json_sink, level="INFO")
