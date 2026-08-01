"""Transform a live `aws ecs describe-task-definition` JSON document into a
document suitable for `aws ecs register-task-definition --cli-input-json`,
with the loader container's image replaced.

Reads from an input file and writes to an output file - never stdin/stdout -
so this can be composed in a shell pipeline via plain file paths
(scripts/run-award-attachment-loader.sh) without the classic
`python3 - <<'PYEOF' ... PYEOF <<< "$SOME_VAR"` trap: a script invoked with
`python3 -` reads its own program text from stdin, so a later attempt to
also read data from stdin (`json.load(sys.stdin)`) finds stdin already at
EOF. File paths sidestep the problem entirely.

Usage:
    uv run python scripts/transform_loader_task_definition.py \
        --input current-taskdef.json \
        --output new-taskdef.json \
        --container-name loader \
        --image-uri 123456789012.dkr.ecr.us-east-1.amazonaws.com/loader:tag \
        --family research-archive-platform-dev-loader
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

# `register-task-definition` rejects these if present - they are properties
# of a specific, already-registered revision, not inputs to registering a
# new one.
UNSUPPORTED_REGISTRATION_FIELDS = (
    "taskDefinitionArn",
    "revision",
    "status",
    "requiresAttributes",
    "compatibilities",
    "registeredAt",
    "registeredBy",
)


class TaskDefinitionTransformError(ValueError):
    """Raised for any input that cannot be safely transformed - never
    partially applied, never silently ignored."""


def load_task_definition(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise TaskDefinitionTransformError(
            f"{path} is empty - expected a task-definition JSON document "
            "(e.g. the output of `aws ecs describe-task-definition`)."
        )
    try:
        taskdef = json.loads(text)
    except json.JSONDecodeError as error:
        raise TaskDefinitionTransformError(
            f"{path} does not contain valid JSON: {error}"
        ) from error
    if not isinstance(taskdef, dict):
        raise TaskDefinitionTransformError(
            f"{path} must contain a JSON object, got {type(taskdef).__name__}."
        )
    return taskdef


def validate_task_definition(
    taskdef: dict,
    *,
    container_name: str,
    expected_family: str | None = None,
) -> None:
    if expected_family is not None:
        actual_family = taskdef.get("family")
        if actual_family != expected_family:
            raise TaskDefinitionTransformError(
                f"Task definition family is {actual_family!r}, expected "
                f"{expected_family!r}."
            )

    containers = taskdef.get("containerDefinitions")
    if not isinstance(containers, list):
        raise TaskDefinitionTransformError(
            "Task definition has no containerDefinitions list."
        )
    names = [c.get("name") for c in containers if isinstance(c, dict)]
    if container_name not in names:
        raise TaskDefinitionTransformError(
            f"No container named {container_name!r} found in "
            f"containerDefinitions (found: {names!r})."
        )


def strip_unsupported_fields(taskdef: dict) -> dict:
    return {
        key: value
        for key, value in taskdef.items()
        if key not in UNSUPPORTED_REGISTRATION_FIELDS
    }


def replace_container_image(
    taskdef: dict, *, container_name: str, image_uri: str
) -> dict:
    """Returns a new task-definition dict with only the named container's
    image replaced. Every other container (and every other field of the
    matching container) is left exactly as it was."""
    new_containers = []
    for container in taskdef["containerDefinitions"]:
        if container.get("name") == container_name:
            container = {**container, "image": image_uri}
        new_containers.append(container)
    return {**taskdef, "containerDefinitions": new_containers}


def transform(
    taskdef: dict,
    *,
    container_name: str,
    image_uri: str,
    expected_family: str | None = None,
) -> dict:
    validate_task_definition(
        taskdef, container_name=container_name, expected_family=expected_family
    )
    taskdef = strip_unsupported_fields(taskdef)
    taskdef = replace_container_image(
        taskdef, container_name=container_name, image_uri=image_uri
    )
    return taskdef


def parse_args(arguments: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--container-name", required=True, type=str)
    parser.add_argument("--image-uri", required=True, type=str)
    parser.add_argument(
        "--family",
        type=str,
        default=None,
        help="If given, the input task definition's family must match "
        "exactly, or the transform is rejected before anything is written.",
    )
    return parser.parse_args(arguments)


def main() -> None:
    args = parse_args()
    taskdef = load_task_definition(args.input)
    new_taskdef = transform(
        taskdef,
        container_name=args.container_name,
        image_uri=args.image_uri,
        expected_family=args.family,
    )
    args.output.write_text(json.dumps(new_taskdef), encoding="utf-8")


if __name__ == "__main__":
    main()
