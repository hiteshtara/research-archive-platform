import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

from archive_etl.config.settings import load_settings
from archive_etl.extract.excel import read_excel_file
from archive_etl.transform.irb import transform_irb
from archive_etl.upload.s3 import upload_file
from archive_etl.validate.irb import validate_irb


def run_irb_export(input_file: str) -> None:
    settings = load_settings()

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")

    export_dir = Path("exports/irb")
    validation_dir = Path("exports/validation")

    export_dir.mkdir(parents=True, exist_ok=True)
    validation_dir.mkdir(parents=True, exist_ok=True)

    source_df = read_excel_file(input_file)
    transformed_df = transform_irb(source_df)
    validation_report = validate_irb(transformed_df)

    validation_file = validation_dir / f"irb_validation_{timestamp}.json"
    validation_file.write_text(
        json.dumps(validation_report, indent=2, default=str)
    )

    print(json.dumps(validation_report, indent=2))

    if not validation_report["valid"]:
        raise RuntimeError(
            f"IRB validation failed. Review {validation_file}"
        )

    parquet_file = export_dir / f"irb_protocols_{timestamp}.parquet"
    transformed_df.to_parquet(parquet_file, index=False)

    bucket_name = settings["aws"]["data_bucket"]
    region = settings["aws"]["region"]

    parquet_key = f"landing/irb/{parquet_file.name}"
    validation_key = f"validation/irb/{validation_file.name}"

    parquet_location = upload_file(
        parquet_file,
        bucket_name,
        parquet_key,
        region,
    )

    validation_location = upload_file(
        validation_file,
        bucket_name,
        validation_key,
        region,
    )

    print(f"IRB export uploaded: {parquet_location}")
    print(f"Validation report uploaded: {validation_location}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Research Archive Platform local export utility."
    )

    parser.add_argument(
        "domain",
        choices=["irb"],
        help="Data domain to export.",
    )

    parser.add_argument(
        "--input",
        required=True,
        help="Path to the local Excel source file.",
    )

    args = parser.parse_args()

    if args.domain == "irb":
        run_irb_export(args.input)


if __name__ == "__main__":
    main()
