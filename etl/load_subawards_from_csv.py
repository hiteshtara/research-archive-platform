from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import pandas as pd
from loguru import logger
from pandas.errors import EmptyDataError
from sqlalchemy import text
from sqlalchemy.engine import Connection

from archive_etl.upload.bulk_copy import bulk_copy_dataframe
from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine


DOWNLOAD_DIR = Path.home() / "Downloads"

SOURCE_COLUMN_RENAMES = {
    "update_timestamp": "source_update_timestamp",
    "update_user": "source_update_user",
    "ver_nbr": "source_version_number",
    "obj_id": "source_object_id",
    "document_update_timestamp": "document_source_update_timestamp",
    "document_update_user": "document_source_update_user",
    "document_ver_nbr": "document_source_version_number",
    "document_obj_id": "document_source_object_id",
}


@dataclass(frozen=True)
class DatasetSpec:
    key: str
    file_name: str
    table_name: str
    columns: tuple[str, ...]
    primary_key: str
    required_values: tuple[str, ...]
    numeric_columns: tuple[str, ...] = ()
    date_columns: tuple[str, ...] = ()
    allow_empty: bool = False
    parent_column: str | None = "subaward_id"

    @property
    def path(self) -> Path:
        return DOWNLOAD_DIR / self.file_name


DATASETS = (
    DatasetSpec(
        key="subawards",
        file_name="subawards.csv",
        table_name="subaward",
        columns=(
            "subaward_id", "document_number", "sequence_number",
            "subaward_code", "organization_id", "start_date", "end_date",
            "subaward_type_code", "purchase_order_num", "title",
            "status_code", "status_description", "account_number",
            "vendor_number", "requisitioner_id", "requisitioner_unit",
            "archive_location", "closeout_date", "comments",
            "site_investigator", "cost_type", "date_of_fully_executed",
            "requisition_number", "fed_award_proj_desc", "f_and_a_rate",
            "de_minimus", "subaward_sequence_status", "ffata_required",
            "fsrs_subaward_number", "award_prime_sponsor_name",
            "award_sponsor_name", "extension_date_received",
            "update_timestamp", "update_user", "ver_nbr", "obj_id",
            "document_update_timestamp", "document_update_user",
            "document_ver_nbr", "document_obj_id",
        ),
        primary_key="subaward_id",
        required_values=("subaward_id", "subaward_code", "sequence_number"),
        numeric_columns=(
            "subaward_id", "sequence_number", "subaward_type_code",
            "status_code", "site_investigator", "f_and_a_rate", "ver_nbr",
            "document_ver_nbr",
        ),
        date_columns=(
            "start_date", "end_date", "closeout_date",
            "date_of_fully_executed", "extension_date_received",
            "update_timestamp", "document_update_timestamp",
        ),
        parent_column=None,
    ),
    DatasetSpec(
        key="amounts",
        file_name="subaward_amounts.csv",
        table_name="subaward_amount",
        columns=(
            "subaward_amount_info_id", "subaward_id", "subaward_code",
            "sequence_number", "obligated_amount", "obligated_change",
            "obligated_change_direct", "obligated_change_indirect",
            "anticipated_amount", "anticipated_change",
            "anticipated_change_direct", "anticipated_change_indirect",
            "rate", "effective_date", "modification_effective_date",
            "modification_number", "modification_type_code",
            "modification_type_description", "performance_start_date",
            "performance_end_date", "purchase_order_num", "comments",
            "file_data_id", "file_name", "mime_type", "update_timestamp",
            "update_user", "ver_nbr", "obj_id",
        ),
        primary_key="subaward_amount_info_id",
        required_values=(
            "subaward_amount_info_id", "subaward_id", "subaward_code",
            "sequence_number",
        ),
        numeric_columns=(
            "subaward_amount_info_id", "subaward_id", "sequence_number",
            "obligated_amount", "obligated_change",
            "obligated_change_direct", "obligated_change_indirect",
            "anticipated_amount", "anticipated_change",
            "anticipated_change_direct", "anticipated_change_indirect",
            "rate", "ver_nbr",
        ),
        date_columns=(
            "effective_date", "modification_effective_date",
            "performance_start_date", "performance_end_date",
            "update_timestamp",
        ),
    ),
    DatasetSpec(
        key="contacts",
        file_name="subaward_contacts.csv",
        table_name="subaward_contact",
        columns=(
            "subaward_contact_id", "subaward_id", "subaward_code",
            "sequence_number", "contact_type_code", "rolodex_id",
            "requisitioner_id", "update_timestamp", "update_user",
            "ver_nbr", "obj_id",
        ),
        primary_key="subaward_contact_id",
        required_values=(
            "subaward_contact_id", "subaward_id", "subaward_code",
            "sequence_number",
        ),
        numeric_columns=(
            "subaward_contact_id", "subaward_id", "sequence_number",
            "rolodex_id", "ver_nbr",
        ),
        date_columns=("update_timestamp",),
    ),
    DatasetSpec(
        key="custom_data",
        file_name="subaward_custom_data.csv",
        table_name="subaward_custom_data",
        columns=(
            "subaward_custom_data_id", "subaward_id", "subaward_code",
            "sequence_number", "custom_attribute_id", "value",
            "update_timestamp", "update_user", "ver_nbr", "obj_id",
        ),
        primary_key="subaward_custom_data_id",
        required_values=(
            "subaward_custom_data_id", "subaward_id", "subaward_code",
            "sequence_number",
        ),
        numeric_columns=(
            "subaward_custom_data_id", "subaward_id", "sequence_number",
            "custom_attribute_id", "ver_nbr",
        ),
        date_columns=("update_timestamp",),
    ),
    DatasetSpec(
        key="funding",
        file_name="subaward_funding.csv",
        table_name="subaward_funding",
        columns=(
            "subaward_funding_source_id", "subaward_id", "subaward_code",
            "sequence_number", "award_id", "update_timestamp",
            "update_user", "ver_nbr", "obj_id",
        ),
        primary_key="subaward_funding_source_id",
        required_values=(
            "subaward_funding_source_id", "subaward_id", "subaward_code",
            "sequence_number",
        ),
        numeric_columns=(
            "subaward_funding_source_id", "subaward_id", "sequence_number",
            "award_id", "ver_nbr",
        ),
        date_columns=("update_timestamp",),
    ),
    DatasetSpec(
        key="attachments",
        file_name="subaward_attachments.csv",
        table_name="subaward_attachment",
        columns=(
            "attachment_id", "subaward_id", "subaward_code",
            "sequence_number", "attachment_type_code",
            "attachment_type_description", "document_id", "file_data_id",
            "file_name", "mime_type", "document_status_code", "description",
            "last_update_timestamp", "last_update_user", "update_timestamp",
            "update_user", "ver_nbr", "obj_id",
        ),
        primary_key="attachment_id",
        required_values=(
            "attachment_id", "subaward_id", "subaward_code",
            "sequence_number",
        ),
        numeric_columns=(
            "attachment_id", "subaward_id", "sequence_number",
            "attachment_type_code", "document_id", "ver_nbr",
        ),
        date_columns=("last_update_timestamp", "update_timestamp"),
    ),
    DatasetSpec(
        key="closeout",
        file_name="subaward_closeout.csv",
        table_name="subaward_closeout",
        columns=(
            "subaward_closeout_id", "subaward_id", "subaward_code",
            "sequence_number", "closeout_number", "closeout_type_code",
            "date_requested", "date_followup", "date_received", "comments",
            "update_timestamp", "update_user", "ver_nbr", "obj_id",
        ),
        primary_key="subaward_closeout_id",
        required_values=(
            "subaward_closeout_id", "subaward_id", "subaward_code",
            "sequence_number",
        ),
        numeric_columns=(
            "subaward_closeout_id", "subaward_id", "sequence_number",
            "closeout_number", "closeout_type_code", "ver_nbr",
        ),
        date_columns=(
            "date_requested", "date_followup", "date_received",
            "update_timestamp",
        ),
        allow_empty=True,
    ),
    DatasetSpec(
        key="reports",
        file_name="subaward_reports.csv",
        table_name="subaward_report",
        columns=(
            "subaward_report_id", "subaward_id", "subaward_code",
            "sequence_number", "report_type_code", "report_type_description",
            "update_timestamp", "update_user", "ver_nbr", "obj_id",
        ),
        primary_key="subaward_report_id",
        required_values=(
            "subaward_report_id", "subaward_id", "subaward_code",
            "sequence_number",
        ),
        numeric_columns=("subaward_id", "sequence_number", "ver_nbr"),
        date_columns=("update_timestamp",),
        allow_empty=True,
    ),
    DatasetSpec(
        key="notepad",
        file_name="subaward_notepad.csv",
        table_name="subaward_notepad",
        columns=(
            "subaward_notepad_id", "subaward_id", "subaward_code",
            "entry_number", "note_topic", "comments", "restricted_view",
            "create_timestamp", "create_user", "update_timestamp",
            "update_user", "ver_nbr", "obj_id",
        ),
        primary_key="subaward_notepad_id",
        required_values=(
            "subaward_notepad_id", "subaward_id", "subaward_code",
        ),
        numeric_columns=(
            "subaward_notepad_id", "subaward_id", "entry_number", "ver_nbr",
        ),
        date_columns=("create_timestamp", "update_timestamp"),
        allow_empty=True,
    ),
    DatasetSpec(
        key="notifications",
        file_name="subaward_notifications.csv",
        table_name="subaward_notification",
        columns=(
            "notification_id", "owning_document_id_fk", "document_number",
            "subaward_code", "notification_type_id", "recipients", "subject",
            "message", "create_timestamp", "update_timestamp", "update_user",
            "ver_nbr", "obj_id",
        ),
        primary_key="notification_id",
        required_values=("notification_id", "owning_document_id_fk"),
        numeric_columns=(
            "notification_id", "owning_document_id_fk",
            "notification_type_id", "ver_nbr",
        ),
        date_columns=("create_timestamp", "update_timestamp"),
        allow_empty=True,
        parent_column="owning_document_id_fk",
    ),
    DatasetSpec(
        key="template_info",
        file_name="subaward_template_info.csv",
        table_name="subaward_template_info",
        columns=(
            "subaward_id", "subaward_code", "sequence_number",
            "sow_or_sub_proposal_budget", "sub_proposal_date",
            "invoice_or_payment_contact", "irb_iacuc_contact",
            "final_stmt_of_costs_contact", "change_requests_contact",
            "sub_change_requests_contact", "termination_contact",
            "sub_termination_contact", "no_cost_extension_contact",
            "perf_site_diff_from_org_addr", "perf_site_same_as_sub_pi_addr",
            "sub_registered_in_ccr", "sub_exempt_from_reporting_comp",
            "parent_duns_number", "parent_congressional_district",
            "exempt_from_rprtg_exec_comp", "copyright_type",
            "automatic_carry_forward", "carry_forward_requests_sent_to",
            "treatment_prgm_income_additive", "applicable_program_regulations",
            "applicable_program_regs_date", "mpi_award",
            "mpi_leadership_plan", "r_and_d", "includes_cost_sharing", "fcio",
            "invoices_emailed", "invoice_address_diff", "invoice_email_diff",
            "fcio_subrec_policy_cd", "animal_flag", "animal_pte_send_cd",
            "animal_pte_nr_cd", "human_flag", "human_subjects",
            "human_exempt_docs", "human_pte_send_cd", "human_pte_nr_cd",
            "human_data_exchange_agree_cd", "human_data_exchange_terms_cd",
            "human_includes_clinical_trials", "additional_terms",
            "treatment_of_income", "data_sharing_attachment",
            "data_sharing_cd", "final_statement_due_cd", "update_timestamp",
            "update_user",
        ),
        primary_key="subaward_id",
        required_values=("subaward_id", "subaward_code", "sequence_number"),
        numeric_columns=(
            "subaward_id", "sequence_number", "invoice_or_payment_contact",
            "irb_iacuc_contact", "final_stmt_of_costs_contact",
            "change_requests_contact", "sub_change_requests_contact",
            "termination_contact", "sub_termination_contact",
            "no_cost_extension_contact", "carry_forward_requests_sent_to",
        ),
        date_columns=(
            "sub_proposal_date", "applicable_program_regs_date",
            "update_timestamp",
        ),
    ),
)


def normalize_column_name(column: str) -> str:
    return column.strip().lower().replace(" ", "_").replace("-", "_")


def empty_dataframe(spec: DatasetSpec) -> pd.DataFrame:
    return pd.DataFrame(columns=list(spec.columns))


def require_files() -> None:
    missing = [
        str(spec.path)
        for spec in DATASETS
        if not spec.allow_empty and not spec.path.exists()
    ]
    if missing:
        raise RuntimeError(
            "Missing required Subaward CSV files:\n" + "\n".join(missing)
        )


def read_csv(spec: DatasetSpec) -> pd.DataFrame:
    logger.info("Reading {}", spec.path)

    if not spec.path.exists():
        if spec.allow_empty:
            logger.info(
                "{} is absent; treating it as a valid empty dataset",
                spec.file_name,
            )
            return empty_dataframe(spec)
        raise RuntimeError(f"Missing required CSV file: {spec.path}")

    try:
        dataframe = pd.read_csv(
            spec.path,
            dtype=str,
            keep_default_na=True,
            na_values=["", "NULL", "null"],
            low_memory=False,
        )
    except EmptyDataError:
        if not spec.allow_empty:
            raise RuntimeError(f"{spec.file_name} is empty") from None
        logger.info(
            "{} contains no header or rows; treating it as a valid empty dataset",
            spec.file_name,
        )
        return empty_dataframe(spec)

    dataframe.columns = [normalize_column_name(c) for c in dataframe.columns]
    dataframe = dataframe.replace(
        {"": None, "NULL": None, "null": None, "NaN": None, "nan": None}
    )
    logger.info("{} rows read from {}", len(dataframe), spec.file_name)
    return dataframe


def prepare_dataset(spec: DatasetSpec, dataframe: pd.DataFrame) -> pd.DataFrame:
    missing = sorted(set(spec.columns) - set(dataframe.columns))
    if missing:
        raise RuntimeError(
            f"{spec.file_name} is missing columns: " + ", ".join(missing)
        )

    for column in spec.numeric_columns:
        dataframe[column] = pd.to_numeric(dataframe[column], errors="coerce")
    for column in spec.date_columns:
        dataframe[column] = pd.to_datetime(dataframe[column], errors="coerce")

    invalid = dataframe[dataframe[list(spec.required_values)].isna().any(axis=1)]
    if not invalid.empty:
        raise RuntimeError(
            f"{spec.file_name} contains {len(invalid)} rows missing required values"
        )

    duplicate_rows = dataframe.duplicated(subset=[spec.primary_key], keep=False)
    if duplicate_rows.any():
        preview = (
            dataframe.loc[duplicate_rows, [spec.primary_key]]
            .head(20)
            .to_string(index=False)
        )
        raise RuntimeError(
            f"{spec.file_name} contains duplicate {spec.primary_key} values\n"
            + preview
        )

    return dataframe[list(spec.columns)].copy()


def validate_parent_relationships(
    subawards: pd.DataFrame,
    datasets: dict[str, pd.DataFrame],
) -> None:
    parent_ids = set(subawards["subaward_id"].astype("int64"))

    for spec in DATASETS:
        if spec.parent_column is None:
            continue

        child = datasets[spec.key]
        if child.empty:
            continue

        child_ids = set(child[spec.parent_column].dropna().astype("int64"))
        orphan_ids = sorted(child_ids - parent_ids)
        if orphan_ids:
            preview = ", ".join(str(value) for value in orphan_ids[:20])
            raise RuntimeError(
                f"{spec.file_name} contains orphan Subaward IDs: {preview}"
            )


def create_load_run(connection: Connection, total_rows: int) -> int:
    load_id = connection.execute(
        text(
            """
            INSERT INTO archive.load_run (
                domain,
                source_system,
                source_file_name,
                rows_read,
                status
            )
            VALUES (
                'SUBAWARD',
                'KUALI',
                'subaward CSV export set',
                :rows_read,
                'STARTED'
            )
            RETURNING load_id
            """
        ),
        {"rows_read": total_rows},
    ).scalar_one()
    return int(load_id)


def clear_existing_data(connection: Connection) -> None:
    logger.info("Clearing existing Subaward archive data")
    connection.execute(
        text(
            """
            TRUNCATE TABLE
                archive.subaward_template_info,
                archive.subaward_notification,
                archive.subaward_notepad,
                archive.subaward_report,
                archive.subaward_closeout,
                archive.subaward_attachment,
                archive.subaward_funding,
                archive.subaward_custom_data,
                archive.subaward_contact,
                archive.subaward_amount,
                archive.subaward;
            """
        )
    )


def load_dataframe(
    connection: Connection,
    dataframe: pd.DataFrame,
    spec: DatasetSpec,
    load_id: int,
) -> int:
    target = dataframe.rename(columns=SOURCE_COLUMN_RENAMES).copy()
    target["load_id"] = load_id

    logger.info("COPY {:<35} {:,} rows", spec.table_name, len(target))
    if target.empty:
        logger.info(
            "Skipping COPY for archive.{} because the dataset is empty",
            spec.table_name,
        )
        return 0

    return bulk_copy_dataframe(
        connection=connection,
        dataframe=target,
        schema="archive",
        table=spec.table_name,
    )


def verify_loaded_data(
    connection: Connection,
    expected_counts: dict[str, int],
) -> None:
    for table_name, expected_count in expected_counts.items():
        actual_count = int(
            connection.execute(
                text(f"SELECT COUNT(*) FROM archive.{table_name}")
            ).scalar_one()
        )
        logger.info(
            "VERIFY {:<35} expected={:,} actual={:,}",
            table_name,
            expected_count,
            actual_count,
        )
        if actual_count != expected_count:
            raise RuntimeError(
                f"archive.{table_name} row-count mismatch: "
                f"expected {expected_count}, found {actual_count}"
            )

    for spec in DATASETS:
        if spec.parent_column is None:
            continue
        orphan_count = int(
            connection.execute(
                text(
                    f"""
                    SELECT COUNT(*)
                    FROM archive.{spec.table_name} child
                    LEFT JOIN archive.subaward parent
                        ON parent.subaward_id = child.{spec.parent_column}
                    WHERE parent.subaward_id IS NULL
                    """
                )
            ).scalar_one()
        )
        logger.info(
            "VERIFY {:<35} orphan rows={:,}",
            spec.table_name,
            orphan_count,
        )
        if orphan_count:
            raise RuntimeError(
                f"archive.{spec.table_name} contains {orphan_count} orphan rows"
            )


def mark_load_complete(
    connection: Connection,
    load_id: int,
    rows_loaded: int,
) -> None:
    connection.execute(
        text(
            """
            UPDATE archive.load_run
               SET status = 'LOADED',
                   rows_staged = :rows_loaded,
                   rows_loaded = :rows_loaded,
                   rows_rejected = 0,
                   completed_at = CURRENT_TIMESTAMP
             WHERE load_id = :load_id
            """
        ),
        {"load_id": load_id, "rows_loaded": rows_loaded},
    )


def main() -> None:
    require_files()
    datasets = {
        spec.key: prepare_dataset(spec, read_csv(spec))
        for spec in DATASETS
    }

    subawards = datasets["subawards"]
    validate_parent_relationships(subawards, datasets)

    expected_counts = {
        spec.table_name: len(datasets[spec.key])
        for spec in DATASETS
    }
    total_rows = sum(expected_counts.values())

    logger.info(
        "Prepared Subaward rows: {}",
        " ".join(
            f"{spec.key}={len(datasets[spec.key]):,}"
            for spec in DATASETS
        ),
    )

    engine = create_postgres_engine()
    apply_migrations(
        engine,
        Path(__file__).resolve().parents[1] / "database" / "migrations",
    )

    with engine.begin() as connection:
        load_id = create_load_run(connection, total_rows)
        clear_existing_data(connection)

        loaded_counts = {
            spec.table_name: load_dataframe(
                connection,
                datasets[spec.key],
                spec,
                load_id,
            )
            for spec in DATASETS
        }
        verify_loaded_data(connection, expected_counts)

        rows_loaded = sum(loaded_counts.values())
        if rows_loaded != total_rows:
            raise RuntimeError(
                f"Loaded row total mismatch: expected {total_rows}, "
                f"loaded {rows_loaded}"
            )
        mark_load_complete(connection, load_id, rows_loaded)

    logger.success(
        "Subaward archive load complete: load_id={} rows_loaded={:,}",
        load_id,
        rows_loaded,
    )


if __name__ == "__main__":
    main()
