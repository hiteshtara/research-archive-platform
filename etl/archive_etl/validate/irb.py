import pandas as pd


REQUIRED_COLUMNS = {
    "protocol_base",
    "protocol_number",
    "protocol_id",
    "title",
    "protocol_status",
    "pi_full_name",
}


def validate_irb(df: pd.DataFrame) -> dict[str, object]:
    missing_columns = sorted(REQUIRED_COLUMNS - set(df.columns))

    duplicate_protocol_bases = 0
    if "protocol_base" in df.columns:
        duplicate_protocol_bases = int(df["protocol_base"].duplicated().sum())

    missing_pi_names = 0
    if "pi_full_name" in df.columns:
        missing_pi_names = int(df["pi_full_name"].isna().sum())

    missing_pi_buids = 0
    if "pi_buid" in df.columns:
        missing_pi_buids = int(df["pi_buid"].isna().sum())

    report = {
        "row_count": int(len(df)),
        "column_count": int(len(df.columns)),
        "missing_required_columns": missing_columns,
        "duplicate_protocol_bases": duplicate_protocol_bases,
        "missing_pi_names": missing_pi_names,
        "missing_pi_buids": missing_pi_buids,
        "valid": not missing_columns and duplicate_protocol_bases == 0,
    }

    return report
