from __future__ import annotations

import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import cast

from scripts.transform_loader_task_definition import (
    TaskDefinitionTransformError,
    load_task_definition,
    main,
    replace_container_image,
    strip_unsupported_fields,
    transform,
    validate_task_definition,
)

OLD_IMAGE = (
    "770203350335.dkr.ecr.us-east-1.amazonaws.com/"
    "research-archive-platform-dev-loader:old-tag"
)

SAMPLE_TASKDEF = {
    "taskDefinitionArn": (
        "arn:aws:ecs:us-east-1:770203350335:task-definition/"
        "research-archive-platform-dev-loader:2"
    ),
    "family": "research-archive-platform-dev-loader",
    "revision": 2,
    "status": "ACTIVE",
    "requiresAttributes": [{"name": "com.amazonaws.ecs.capability.docker-remote-api.1.18"}],
    "compatibilities": ["FARGATE"],
    "registeredAt": "2026-07-31T00:53:43Z",
    "registeredBy": "arn:aws:iam::770203350335:user/someone",
    "networkMode": "awsvpc",
    "containerDefinitions": [
        {
            "name": "loader",
            "image": OLD_IMAGE,
            "essential": True,
            "environment": [{"name": "AWS_REGION", "value": "us-east-1"}],
        },
        {
            "name": "sidecar",
            "image": "770203350335.dkr.ecr.us-east-1.amazonaws.com/some-sidecar:v1",
            "essential": False,
        },
    ],
}

NEW_IMAGE = (
    "770203350335.dkr.ecr.us-east-1.amazonaws.com/"
    "research-archive-platform-dev-loader:20260731T005343Z-b0d475d"
)


class LoadTaskDefinitionTest(unittest.TestCase):
    def test_empty_input_json_raises_clearly(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "empty.json"
            path.write_text("", encoding="utf-8")

            with self.assertRaises(TaskDefinitionTransformError) as ctx:
                load_task_definition(path)

            self.assertIn("empty", str(ctx.exception))

    def test_whitespace_only_input_is_treated_as_empty(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "blank.json"
            path.write_text("   \n\t  \n", encoding="utf-8")

            with self.assertRaises(TaskDefinitionTransformError):
                load_task_definition(path)

    def test_invalid_json_raises_clearly(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "broken.json"
            path.write_text("{not valid json", encoding="utf-8")

            with self.assertRaises(TaskDefinitionTransformError) as ctx:
                load_task_definition(path)

            self.assertIn("valid JSON", str(ctx.exception))

    def test_non_object_json_is_rejected(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "list.json"
            path.write_text("[1, 2, 3]", encoding="utf-8")

            with self.assertRaises(TaskDefinitionTransformError):
                load_task_definition(path)

    def test_valid_input_loads(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "taskdef.json"
            path.write_text(json.dumps(SAMPLE_TASKDEF), encoding="utf-8")

            loaded = load_task_definition(path)

            self.assertEqual(loaded["family"], "research-archive-platform-dev-loader")


class ValidateTaskDefinitionTest(unittest.TestCase):
    def test_rejects_family_mismatch(self) -> None:
        with self.assertRaises(TaskDefinitionTransformError):
            validate_task_definition(
                SAMPLE_TASKDEF,
                container_name="loader",
                expected_family="some-other-family",
            )

    def test_accepts_matching_family(self) -> None:
        validate_task_definition(
            SAMPLE_TASKDEF,
            container_name="loader",
            expected_family="research-archive-platform-dev-loader",
        )

    def test_skips_family_check_when_not_given(self) -> None:
        validate_task_definition(SAMPLE_TASKDEF, container_name="loader")

    def test_rejects_missing_container(self) -> None:
        with self.assertRaises(TaskDefinitionTransformError):
            validate_task_definition(SAMPLE_TASKDEF, container_name="does-not-exist")


class StripUnsupportedFieldsTest(unittest.TestCase):
    def test_unsupported_fields_removed(self) -> None:
        stripped = strip_unsupported_fields(SAMPLE_TASKDEF)

        for field in (
            "taskDefinitionArn",
            "revision",
            "status",
            "requiresAttributes",
            "compatibilities",
            "registeredAt",
            "registeredBy",
        ):
            self.assertNotIn(field, stripped)

    def test_supported_fields_survive(self) -> None:
        stripped = strip_unsupported_fields(SAMPLE_TASKDEF)

        self.assertEqual(stripped["family"], "research-archive-platform-dev-loader")
        self.assertEqual(stripped["networkMode"], "awsvpc")
        self.assertIn("containerDefinitions", stripped)

    def test_does_not_mutate_input(self) -> None:
        original = json.loads(json.dumps(SAMPLE_TASKDEF))

        strip_unsupported_fields(SAMPLE_TASKDEF)

        self.assertEqual(SAMPLE_TASKDEF, original)


class ReplaceContainerImageTest(unittest.TestCase):
    def test_loader_image_replaced(self) -> None:
        result = replace_container_image(
            SAMPLE_TASKDEF, container_name="loader", image_uri=NEW_IMAGE
        )

        loader = next(
            c for c in result["containerDefinitions"] if c["name"] == "loader"
        )
        self.assertEqual(loader["image"], NEW_IMAGE)

    def test_non_loader_containers_preserved(self) -> None:
        result = replace_container_image(
            SAMPLE_TASKDEF, container_name="loader", image_uri=NEW_IMAGE
        )

        sidecar = next(
            c for c in result["containerDefinitions"] if c["name"] == "sidecar"
        )
        original_sidecar = next(
            c
            for c in cast(list, SAMPLE_TASKDEF["containerDefinitions"])
            if c["name"] == "sidecar"
        )
        self.assertEqual(sidecar, original_sidecar)

    def test_loader_container_other_fields_preserved(self) -> None:
        result = replace_container_image(
            SAMPLE_TASKDEF, container_name="loader", image_uri=NEW_IMAGE
        )

        loader = next(
            c for c in result["containerDefinitions"] if c["name"] == "loader"
        )
        self.assertEqual(loader["essential"], True)
        self.assertEqual(
            loader["environment"], [{"name": "AWS_REGION", "value": "us-east-1"}]
        )

    def test_does_not_mutate_input(self) -> None:
        original = json.loads(json.dumps(SAMPLE_TASKDEF))

        replace_container_image(SAMPLE_TASKDEF, container_name="loader", image_uri=NEW_IMAGE)

        self.assertEqual(SAMPLE_TASKDEF, original)


class TransformTest(unittest.TestCase):
    def test_full_transform_strips_and_replaces(self) -> None:
        result = transform(
            SAMPLE_TASKDEF,
            container_name="loader",
            image_uri=NEW_IMAGE,
            expected_family="research-archive-platform-dev-loader",
        )

        self.assertNotIn("taskDefinitionArn", result)
        self.assertNotIn("revision", result)
        loader = next(
            c for c in result["containerDefinitions"] if c["name"] == "loader"
        )
        self.assertEqual(loader["image"], NEW_IMAGE)
        sidecar = next(
            c for c in result["containerDefinitions"] if c["name"] == "sidecar"
        )
        self.assertEqual(
            sidecar["image"],
            cast(list, SAMPLE_TASKDEF["containerDefinitions"])[1]["image"],
        )

    def test_rejects_family_mismatch_before_any_change(self) -> None:
        with self.assertRaises(TaskDefinitionTransformError):
            transform(
                SAMPLE_TASKDEF,
                container_name="loader",
                image_uri=NEW_IMAGE,
                expected_family="wrong-family",
            )


class MainCliTest(unittest.TestCase):
    def test_end_to_end_via_files(self) -> None:
        with TemporaryDirectory() as tmp:
            input_path = Path(tmp) / "input.json"
            output_path = Path(tmp) / "output.json"
            input_path.write_text(json.dumps(SAMPLE_TASKDEF), encoding="utf-8")

            import sys

            argv = [
                "transform_loader_task_definition.py",
                "--input",
                str(input_path),
                "--output",
                str(output_path),
                "--container-name",
                "loader",
                "--image-uri",
                NEW_IMAGE,
                "--family",
                "research-archive-platform-dev-loader",
            ]
            original_argv = sys.argv
            sys.argv = argv
            try:
                main()
            finally:
                sys.argv = original_argv

            result = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertNotIn("revision", result)
            loader = next(
                c for c in result["containerDefinitions"] if c["name"] == "loader"
            )
            self.assertEqual(loader["image"], NEW_IMAGE)


if __name__ == "__main__":
    unittest.main()
