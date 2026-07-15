from __future__ import annotations

from dataclasses import dataclass

import pandas as pd


DATE_COLUMNS = [
    "irb_recv_dt",
    "irb_claim_dt",
    "irb_determine_dt",
    "irb_approval_dt",
    "irb_new_exp_dt",
    "irb_closure_dt",
    "irb_auth_dt",
    "irb_req_mod_dt1",
    "irb_response_dt1",
    "irb_req_mod_dt2",
    "irb_response_dt2",
    "irb_req_mod_dt3",
    "irb_response_dt3",
    "irb_req_mod_dt4",
    "irb_response_dt4",
    "irb_req_mod_dt5",
    "irb_response_dt5",
    "irb_req_mod_dt6",
    "irb_response_dt6",
]

FUNDING_COLUMNS = [
    f"funding_src{number}"
    for number in range(1, 16)
]

PROTOCOL_COLUMNS = [
    "active_ind",
    "crc_protocol_num",
    "protocol_id",
    "document_number",
    "protocol_base",
    "protocol_number",
    "sequence_number",
    "title",
    "proto_type_cd",
    "proto_type_desc",
    "proto_stat_cd",
    "proto_stat_desc",
    "fda_ide_num",
    "ohrp_categories",
    "summary_keywords",
    "pi_id",
    "pi_email_address",
    "pi_affil_typ_cd",
    "pi_affil_typ_desc",
    "fund_center_num",
    "school_num",
    "irb_analyst_id",
    "irb_advisor1_id",
    "irb_recv_dt",
    "irb_claim_dt",
    "irb_determine_dt",
    "irb_approval_dt",
    "irb_new_exp_dt",
    "irb_closure_dt",
    "irb_auth_dt",
    "irb_rcd_keep_box",
    "max_expire_ind",
    "expired_or_less30",
    "working_days",
    "calendar_days",
    "irb_days",
    "pi_days",
    "funding_src_cnt",
]

SUBMISSION_COLUMNS = [
    "protocol_id",
    "protocol_base",
    "protocol_number",
    "sequence_number",
    "submit_number",
    "submit_type_cd",
    "submit_type_desc",
    "submit_stat_cd",
    "submit_stat_desc",
    "event_type_cd",
    "event_type_desc",
    "review_type_cd",
    "review_type_desc",
]


@dataclass(frozen=True)
class CompositeDatasets:
    protocols: pd.DataFrame
    submissions: pd.DataFrame
    funding: pd.DataFrame
    timeline: pd.DataFrame
    dropped_null_columns: list[str]


def normalize_column_name(column: object) -> str:
    return (
        str(column)
        .strip()
        .lower()
        .replace(" ", "_")
    )


def parse_yyyymmdd(series: pd.Series) -> pd.Series:
    text = (
        series
        .astype("string")
        .str.strip()
        .str.replace(r"\.0$", "", regex=True)
    )

    return pd.to_datetime(
        text,
        format="%Y%m%d",
        errors="coerce",
    )


def clean_text_columns(dataframe: pd.DataFrame) -> pd.DataFrame:
    result = dataframe.copy()

    for column in result.select_dtypes(
        include=["object", "string"]
    ).columns:
        result[column] = (
            result[column]
            .astype("string")
            .str.strip()
            .replace("", pd.NA)
        )

    return result


def select_existing_columns(
    dataframe: pd.DataFrame,
    requested_columns: list[str],
) -> pd.DataFrame:
    columns = [
        column
        for column in requested_columns
        if column in dataframe.columns
    ]

    return dataframe[columns].copy()


def build_protocols(dataframe: pd.DataFrame) -> pd.DataFrame:
    """
    Keep one row for every historical protocol_id.

    protocol_base groups the protocol family.
    protocol_id identifies each historical version.
    Child submissions, funding, and timeline rows reference protocol_id,
    so all protocol IDs must remain in the parent dataset.
    """
    sort_columns = [
        column
        for column in [
            "protocol_id",
            "submit_number",
            "sequence_number",
        ]
        if column in dataframe.columns
    ]

    ranked = dataframe.sort_values(
        sort_columns,
        ascending=True,
        na_position="first",
    )

    versions = ranked.drop_duplicates(
        subset=["protocol_id"],
        keep="last",
    )

    protocols = select_existing_columns(
        versions,
        PROTOCOL_COLUMNS,
    )

    return protocols.reset_index(drop=True)


def build_submissions(dataframe: pd.DataFrame) -> pd.DataFrame:
    submissions = select_existing_columns(
        dataframe,
        SUBMISSION_COLUMNS,
    )

    required_key_columns = [
        column
        for column in [
            "protocol_id",
            "submit_number",
            "submit_type_cd",
            "event_type_cd",
            "review_type_cd",
        ]
        if column in submissions.columns
    ]

    if required_key_columns:
        submissions = submissions.drop_duplicates(
            subset=required_key_columns,
            keep="last",
        )
    else:
        submissions = submissions.drop_duplicates()

    if "submit_number" in submissions.columns:
        submissions = submissions[
            submissions["submit_number"].notna()
        ]

    return submissions.reset_index(drop=True)


def build_funding(dataframe: pd.DataFrame) -> pd.DataFrame:
    identifier_columns = [
        column
        for column in [
            "protocol_id",
            "protocol_base",
            "protocol_number",
        ]
        if column in dataframe.columns
    ]

    funding_columns = [
        column
        for column in FUNDING_COLUMNS
        if column in dataframe.columns
    ]

    if not funding_columns:
        return pd.DataFrame(
            columns=[
                *identifier_columns,
                "funding_sequence",
                "funding_source",
            ]
        )

    funding = dataframe[
        identifier_columns + funding_columns
    ].melt(
        id_vars=identifier_columns,
        value_vars=funding_columns,
        var_name="funding_column",
        value_name="funding_source",
    )

    funding["funding_source"] = (
        funding["funding_source"]
        .astype("string")
        .str.strip()
        .replace("", pd.NA)
    )

    funding = funding[
        funding["funding_source"].notna()
    ].copy()

    funding["funding_sequence"] = (
        funding["funding_column"]
        .str.extract(r"(\d+)$")[0]
        .astype("Int64")
    )

    funding = funding.drop(
        columns=["funding_column"]
    )

    funding = funding.drop_duplicates(
        subset=[
            *identifier_columns,
            "funding_source",
        ]
    )

    return funding.reset_index(drop=True)


def build_timeline(dataframe: pd.DataFrame) -> pd.DataFrame:
    identifier_columns = [
        column
        for column in [
            "protocol_id",
            "protocol_base",
            "protocol_number",
        ]
        if column in dataframe.columns
    ]

    event_definitions: list[tuple[str, str, int | None]] = [
        ("irb_recv_dt", "IRB Received", None),
        ("irb_claim_dt", "IRB Claimed", None),
        ("irb_determine_dt", "IRB Determination", None),
        ("irb_approval_dt", "IRB Approved", None),
        ("irb_new_exp_dt", "Expiration", None),
        ("irb_closure_dt", "IRB Closed", None),
        ("irb_auth_dt", "IRB Authorized", None),
    ]

    for sequence in range(1, 7):
        event_definitions.extend([
            (
                f"irb_req_mod_dt{sequence}",
                "Modification Requested",
                sequence,
            ),
            (
                f"irb_response_dt{sequence}",
                "PI Response Received",
                sequence,
            ),
        ])

    event_frames: list[pd.DataFrame] = []

    for column, event_type, event_sequence in event_definitions:
        if column not in dataframe.columns:
            continue

        event = dataframe[
            identifier_columns + [column]
        ].copy()

        event = event.rename(
            columns={column: "event_date"}
        )

        event = event[
            event["event_date"].notna()
        ]

        if event.empty:
            continue

        event["event_type"] = event_type
        event["event_sequence"] = event_sequence
        event["source_column"] = column

        event_frames.append(event)

    if not event_frames:
        return pd.DataFrame(
            columns=[
                *identifier_columns,
                "event_date",
                "event_type",
                "event_sequence",
                "source_column",
            ]
        )

    timeline = pd.concat(
        event_frames,
        ignore_index=True,
    )

    timeline = timeline.drop_duplicates(
        subset=[
            *identifier_columns,
            "event_date",
            "event_type",
            "event_sequence",
        ]
    )

    timeline = timeline.sort_values(
        [
            "protocol_base",
            "event_date",
            "event_sequence",
        ],
        na_position="last",
    )

    return timeline.reset_index(drop=True)


def transform_composite(
    source: pd.DataFrame,
) -> CompositeDatasets:
    dataframe = source.copy()

    dataframe.columns = [
        normalize_column_name(column)
        for column in dataframe.columns
    ]

    dataframe = clean_text_columns(dataframe)

    null_columns = [
        column
        for column in dataframe.columns
        if dataframe[column].isna().all()
    ]

    dataframe = dataframe.drop(
        columns=null_columns
    )

    for column in DATE_COLUMNS:
        if column in dataframe.columns:
            dataframe[column] = parse_yyyymmdd(
                dataframe[column]
            )

    for column in [
        "protocol_id",
        "sequence_number",
        "submit_number",
        "working_days",
        "calendar_days",
        "irb_days",
        "pi_days",
        "funding_src_cnt",
    ]:
        if column in dataframe.columns:
            dataframe[column] = pd.to_numeric(
                dataframe[column],
                errors="coerce",
            ).astype("Int64")

    return CompositeDatasets(
        protocols=build_protocols(dataframe),
        submissions=build_submissions(dataframe),
        funding=build_funding(dataframe),
        timeline=build_timeline(dataframe),
        dropped_null_columns=sorted(null_columns),
    )
