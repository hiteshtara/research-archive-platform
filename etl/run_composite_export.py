from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd
from loguru import logger

from archive_etl.config.settings import load_settings
from archive_etl.transform.irb_composite import (
    transform_composite,
)
from archive_etl.upload.s3 import upload_file


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Transform the Kuali PROTOCOL_COMPOSITE export."
    )

    parser.add_argument(
        "--input",
        required=True,
        help="Path to the PROTOCOL_COMPOSITE Excel workbook.",
    )

    parser.add_argument(
        "--sheet",
        default="PROTOCOL_COMPOSITE",
        help="Workbook sheet containing the composite data.",
    )

    args = parser.parse_args()

    input_path = Path(args.input)

    if not input_path.exists():
        raise FileNotFoundError(
            f"Composite workbook not found: {input_path}"
        )

    logger.info(
        "Reading composite workbook: {}",
        input_path,
    )

    source = pd.read_excel(
        input_path,
        sheet_name=args.sheet,
        dtype="object",
    )

    logger.info(
        "Source shape: {} rows, {} columns",
        len(source),
        len(source.columns),
    )

    datasets = transform_composite(source)

    timestamp = datetime.now(
        timezone.utc
    ).strftime("%Y%m%dT%H%M%SZ")

    export_directory = Path(
        "exports/irb-composite"
    ) / timestamp

    export_directory.mkdir(
        parents=True,
        exist_ok=True,
    )

    files = {
        "protocols": (
            datasets.protocols,
            export_directory / "irb_protocols.parquet",
        ),
        "submissions": (
            datasets.submissions,
            export_directory / "irb_submissions.parquet",
        ),
        "funding": (
            datasets.funding,
            export_directory / "irb_funding_sources.parquet",
        ),
        "timeline": (
            datasets.timeline,
            export_directory / "irb_timeline_events.parquet",
        ),
    }

    for name, (dataframe, file_path) in files.items():
        dataframe.to_parquet(
            file_path,
            index=False,
        )

        logger.info(
            "{}: {} rows written to {}",
            name,
            len(dataframe),
            file_path,
        )

    validation = {
        "source_file": input_path.name,
        "source_rows": int(len(source)),
        "source_columns": int(len(source.columns)),
        "dropped_all_null_columns": (
            datasets.dropped_null_columns
        ),
        "protocol_rows": int(len(datasets.protocols)),
        "submission_rows": int(len(datasets.submissions)),
        "funding_rows": int(len(datasets.funding)),
        "timeline_rows": int(len(datasets.timeline)),
        "duplicate_protocol_ids": int(
            datasets.protocols["protocol_id"]
            .duplicated()
            .sum()
        ),
    }

    validation_path = (
        export_directory / "validation.json"
    )

    validation_path.write_text(
        json.dumps(
            validation,
            indent=2,
            default=str,
        )
    )

    print(
        json.dumps(
            validation,
            indent=2,
        )
    )

    if validation["duplicate_protocol_ids"] != 0:
        raise RuntimeError(
            "Composite protocol output contains duplicate protocol_id values."
        )

    settings = load_settings()
    bucket_name = settings["aws"]["data_bucket"]
    region = settings["aws"]["region"]

    prefix = f"landing/irb-composite/{timestamp}"

    for _, (_, file_path) in files.items():
        location = upload_file(
            file_path,
            bucket_name,
            f"{prefix}/{file_path.name}",
            region,
        )

        logger.info(
            "Uploaded: {}",
            location,
        )

    validation_location = upload_file(
        validation_path,
        bucket_name,
        f"validation/irb-composite/{timestamp}/validation.json",
        region,
    )

    logger.info(
        "Validation uploaded: {}",
        validation_location,
    )


if __name__ == "__main__":
    main()
