import pandas as pd


COLUMN_ALIASES = {
    "STUDY_ID": "study_id",
    "PROTOCOL_BASE": "protocol_base",
    "PROTOCOL_NUMBER": "protocol_number",
    "PROTOCOL_ID": "protocol_id",
    "TITLE": "title",
    "PROTOCOL_TYPE_CODE": "protocol_type_code",
    "PROTOCOL_TYPE": "protocol_type",
    "PROTOCOL_STATUS_CODE": "protocol_status_code",
    "PROTOCOL_STATUS": "protocol_status",
    "APPROVAL_DATE": "approval_date",
    "PI_BUID": "pi_buid",
    "PI_FIRST_NAME": "pi_first_name",
    "PI_LAST_NAME": "pi_last_name",
    "PI_FULL_NAME": "pi_full_name",
    "PI_EMAIL": "pi_email",
    "PI_BUID_MISSING": "pi_buid_missing",
}


def transform_irb(df: pd.DataFrame) -> pd.DataFrame:
    result = df.copy()

    result.columns = [str(column).strip().upper() for column in result.columns]
    result = result.rename(columns=COLUMN_ALIASES)

    for column in (
        "study_id",
        "protocol_base",
        "protocol_number",
        "pi_buid",
        "pi_first_name",
        "pi_last_name",
        "pi_full_name",
        "pi_email",
        "protocol_type",
        "protocol_status",
        "title",
    ):
        if column in result.columns:
            result[column] = result[column].astype("string").str.strip()

    if "approval_date" in result.columns:
        result["approval_date"] = pd.to_datetime(
            result["approval_date"],
            errors="coerce",
        )

    if "pi_buid_missing" not in result.columns:
        result["pi_buid_missing"] = result["pi_buid"].isna().map(
            {True: "Y", False: "N"}
        )

    return result
