from pathlib import Path

import boto3


def upload_file(
    file_path: str | Path,
    bucket_name: str,
    object_key: str,
    region: str = "us-east-1",
) -> str:
    path = Path(file_path)

    if not path.exists():
        raise FileNotFoundError(f"Upload file not found: {path}")

    s3_client = boto3.client("s3", region_name=region)
    s3_client.upload_file(str(path), bucket_name, object_key)

    return f"s3://{bucket_name}/{object_key}"
