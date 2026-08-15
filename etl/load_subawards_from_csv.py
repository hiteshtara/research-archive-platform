from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path

import boto3
import pandas as pd
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection, Engine

from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.pipeline.sources import OracleDataSource
from archive_etl.reference_data import _upsert_rows
from archive_etl.upload.bulk_copy import bulk_copy_dataframe
from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine
from archive_etl.utils.redaction import redact_error_message


def _resolve_project_root() -> Path:
    """Locate the directory containing oracle/subaward/ and
    database/migrations/ relative to this file - mirrors
    load_negotiations_from_csv.py's own _resolve_project_root(): the
    local repo checkout (this file at <repo>/etl/load_subawards_from_csv.py,
    project root one level up) vs. the ECS loader container image (this
    file copied flatly to /app/load_subawards_from_csv.py alongside
    oracle/ and database/migrations/ copied directly under /app - see
    etl/Dockerfile.loader), where the project root is this file's own
    parent directory. This loader had never been run in a container
    before this fix - the naive parents[1] resolution silently worked
    locally and only broke inside the ECS image."""
    container_root = Path(__file__).resolve().parent
    if (container_root / "oracle").is_dir():
        return container_root
    return Path(__file__).resolve().parents[1]


PROJECT_ROOT = _resolve_project_root()

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
    oracle_sql_file: str
    numeric_columns: tuple[str, ...] = ()
    date_columns: tuple[str, ...] = ()
    parent_column: str | None = "subaward_id"

    @property
    def oracle_path(self) -> Path:
        return PROJECT_ROOT / "oracle" / "subaward" / self.oracle_sql_file

    @property
    def archive_columns(self) -> list[str]:
        """self.columns (raw Oracle names) with SOURCE_COLUMN_RENAMES
        applied - the real archive.<table_name> column list, used by
        the targeted per-family UPSERT path."""
        return [SOURCE_COLUMN_RENAMES.get(c, c) for c in self.columns]


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
        oracle_sql_file="export_subawards.sql",
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
        oracle_sql_file="export_subaward_amounts.sql",
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
            "sequence_number", "contact_type_code",
            "contact_type_description", "rolodex_id",
            "requisitioner_id", "update_timestamp", "update_user",
            "ver_nbr", "obj_id",
        ),
        primary_key="subaward_contact_id",
        required_values=(
            "subaward_contact_id", "subaward_id", "subaward_code",
            "sequence_number",
        ),
        oracle_sql_file="export_subaward_contacts.sql",
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
        oracle_sql_file="export_subaward_custom_data.sql",
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
            "sequence_number", "award_id", "award_number",
            "update_timestamp", "update_user", "ver_nbr", "obj_id",
        ),
        primary_key="subaward_funding_source_id",
        required_values=(
            "subaward_funding_source_id", "subaward_id", "subaward_code",
            "sequence_number",
        ),
        oracle_sql_file="export_subaward_funding.sql",
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
        oracle_sql_file="export_subaward_attachments.sql",
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
        oracle_sql_file="export_subaward_closeout.sql",
        numeric_columns=(
            "subaward_closeout_id", "subaward_id", "sequence_number",
            "closeout_number", "closeout_type_code", "ver_nbr",
        ),
        date_columns=(
            "date_requested", "date_followup", "date_received",
            "update_timestamp",
        ),
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
        oracle_sql_file="export_subaward_reports.sql",
        numeric_columns=("subaward_id", "sequence_number", "ver_nbr"),
        date_columns=("update_timestamp",),
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
        oracle_sql_file="export_subaward_notepad.sql",
        numeric_columns=(
            "subaward_notepad_id", "subaward_id", "entry_number", "ver_nbr",
        ),
        date_columns=("create_timestamp", "update_timestamp"),
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
        oracle_sql_file="export_subaward_notifications.sql",
        numeric_columns=(
            "notification_id", "owning_document_id_fk",
            "notification_type_id", "ver_nbr",
        ),
        date_columns=("create_timestamp", "update_timestamp"),
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
        oracle_sql_file="export_subaward_template_info.sql",
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


def parse_args(
    arguments: list[str] | None = None,
) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Load Subaward data from Oracle."
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help=(
            "Truncate every dataset to at most this many rows after "
            "reading, skip cross-dataset validation, and skip the "
            "database write entirely (a bounded dry run for testing "
            "connectivity/transform logic - not a partial load)."
        ),
    )
    parser.add_argument(
        "--load-subaward-code",
        dest="subaward_code",
        action="append",
        default=None,
        help=(
            "Load only this Subaward family (repeatable) - every "
            "version and all child rows sharing this SUBAWARD_CODE. "
            "Idempotent per-family UPSERT, never TRUNCATE. Mutually "
            "exclusive with --load-subaward-id/--max-families."
        ),
    )
    parser.add_argument(
        "--load-subaward-id",
        dest="subaward_id",
        type=int,
        action="append",
        default=None,
        help=(
            "Load the whole family that this physical version belongs "
            "to (repeatable) - resolved to its SUBAWARD_CODE first, "
            "then loaded exactly like --load-subaward-code. Mutually "
            "exclusive with --load-subaward-code/--max-families."
        ),
    )
    parser.add_argument(
        "--max-families",
        type=int,
        default=None,
        help=(
            "Load only the first N Subaward families, ordered ascending "
            "by SUBAWARD_CODE. Same idempotent per-family UPSERT. "
            "Mutually exclusive with --load-subaward-code/"
            "--load-subaward-id."
        ),
    )
    parser.add_argument(
        "--sync-all",
        action="store_true",
        help=(
            "Read every Oracle SUBAWARD_CODE family and UPSERT it into "
            "Postgres (never TRUNCATE) - same idempotent per-family "
            "transaction machinery as --load-subaward-code, applied to "
            "the full population. Intended for unattended/nightly "
            "execution: guarded by a non-blocking PostgreSQL advisory "
            "lock, exits nonzero on any family failure or an unresolved "
            "post-sync reconciliation gap. Mutually exclusive with the "
            "other operation flags."
        ),
    )
    parser.add_argument(
        "--reconcile-only",
        action="store_true",
        help=(
            "Read-only comparison of Oracle's SUBAWARD_CODE population "
            "against archive.subaward - writes nothing. Exits nonzero if "
            "Oracle has codes the archive is missing. Mutually exclusive "
            "with the other operation flags."
        ),
    )
    parser.add_argument(
        "--full-refresh",
        action="store_true",
        help=(
            "DESTRUCTIVE: TRUNCATE every Subaward archive table and "
            "reload the entire dataset from Oracle. Must be given "
            "explicitly - there is no default/no-verb path that does "
            "this. Never used by scheduled/nightly execution; use "
            "--sync-all there instead. Mutually exclusive with the "
            "other operation flags."
        ),
    )
    parser.add_argument(
        "--ecs",
        action="store_true",
        help=(
            "Resolve PostgreSQL/Oracle credentials from Secrets Manager "
            "via the ECS task's environment instead of requiring local "
            "exports - see archive_etl.config.ecs."
        ),
    )
    arguments = parser.parse_args(arguments)

    active_modes = [
        name
        for name, active in (
            ("--load-subaward-code", bool(arguments.subaward_code)),
            ("--load-subaward-id", bool(arguments.subaward_id)),
            ("--max-families", arguments.max_families is not None),
            ("--sync-all", arguments.sync_all),
            ("--reconcile-only", arguments.reconcile_only),
            ("--full-refresh", arguments.full_refresh),
        )
        if active
    ]
    if len(active_modes) > 1:
        parser.error(
            "--load-subaward-code, --load-subaward-id, --max-families, "
            "--sync-all, --reconcile-only, and --full-refresh are "
            "mutually exclusive: " + ", ".join(active_modes)
        )
    if not active_modes and arguments.limit is None:
        parser.error(
            "an explicit operation is required: one of "
            "--load-subaward-code, --load-subaward-id, --max-families, "
            "--sync-all, --reconcile-only, --full-refresh, or --limit "
            "(bounded dry run of the --full-refresh read/transform "
            "path). There is no default/no-verb behavior."
        )

    return arguments


def read_oracle(spec: DatasetSpec) -> pd.DataFrame:
    return OracleDataSource(spec.oracle_path).read()


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
                'Oracle KCOEUS export',
                :rows_read,
                'STARTED'
            )
            RETURNING load_id
            """
        ),
        {"rows_read": total_rows},
    ).scalar_one()
    return int(load_id)


def mark_load_failed(
    engine: Engine,
    load_id: int,
    error_message: str,
) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                UPDATE archive.load_run
                   SET status = 'FAILED',
                       completed_at = CURRENT_TIMESTAMP,
                       error_message = :error_message
                 WHERE load_id = :load_id
                """
            ),
            {
                "load_id": load_id,
                "error_message": redact_error_message(error_message),
            },
        )


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


def _run_ecs_setup() -> None:
    """Minimal --ecs credential setup, mirroring
    load_negotiations_from_csv.py's own (deliberately lean, not the
    fuller startup-validation ceremony load_proposals_from_csv.py uses -
    out of scope for a targeted/proving load)."""
    configure_ecs_environment(boto3.client("secretsmanager"))


def _dataset_by_key(key: str) -> DatasetSpec:
    return next(spec for spec in DATASETS if spec.key == key)


def resolve_target_subaward_codes(
    arguments: argparse.Namespace,
) -> list[str] | None:
    """Returns the explicit list of SUBAWARD_CODE families to load for
    --load-subaward-code/--load-subaward-id/--max-families, or None if
    none of the three were given (the untargeted full-refresh path)."""
    if arguments.subaward_code:
        return sorted(set(arguments.subaward_code))

    if arguments.subaward_id:
        subawards_spec = _dataset_by_key("subawards")
        matched = OracleDataSource(subawards_spec.oracle_path).read_filtered(
            column="subaward_id",
            values=arguments.subaward_id,
        )
        codes = (
            matched["subaward_code"].dropna().astype(str).unique().tolist()
        )
        return sorted(set(codes))

    if arguments.max_families is not None:
        subawards_spec = _dataset_by_key("subawards")
        all_codes = (
            OracleDataSource(subawards_spec.oracle_path)
            .read()["subaward_code"]
            .dropna()
            .astype(str)
            .drop_duplicates()
            .sort_values()
            .tolist()
        )
        return all_codes[: arguments.max_families]

    return None


# Fixed, arbitrary bigint identifying the Subaward sync's advisory lock -
# scoped to this one PostgreSQL session key so at most one --sync-all can
# ever hold it at a time. Not derived from anything; just needs to be
# stable and not collide with another lock user in this codebase.
SUBAWARD_SYNC_ADVISORY_LOCK_KEY = 837_402_615_902


def _try_acquire_sync_lock(connection: Connection) -> bool:
    return bool(
        connection.execute(
            text("SELECT pg_try_advisory_lock(:key)"),
            {"key": SUBAWARD_SYNC_ADVISORY_LOCK_KEY},
        ).scalar_one()
    )


def _release_sync_lock(connection: Connection) -> None:
    connection.execute(
        text("SELECT pg_advisory_unlock(:key)"),
        {"key": SUBAWARD_SYNC_ADVISORY_LOCK_KEY},
    )


def reconcile_subaward_codes(engine: Engine) -> dict[str, object]:
    """Read-only comparison of Oracle's full SUBAWARD_CODE population
    against archive.subaward's - writes nothing. oracle_only codes are
    genuinely missing from the archive (a real gap). rds_only codes exist
    in the archive but have disappeared from Oracle since they were
    loaded - reported as a warning only, never deleted: this archive
    intentionally retains historical rows that no longer exist upstream
    (see clear_existing_data's absence from --sync-all)."""
    subawards_spec = _dataset_by_key("subawards")
    oracle_codes = set(
        OracleDataSource(subawards_spec.oracle_path)
        .read()["subaward_code"]
        .dropna()
        .astype(str)
        .unique()
        .tolist()
    )
    with engine.connect() as connection:
        rds_codes = set(
            connection.execute(
                text("SELECT DISTINCT subaward_code FROM archive.subaward")
            )
            .scalars()
            .all()
        )

    oracle_only = sorted(oracle_codes - rds_codes)
    rds_only = sorted(rds_codes - oracle_codes)

    logger.info(
        "Reconciliation: oracle={:,} rds={:,} oracle_only={:,} rds_only={:,}",
        len(oracle_codes),
        len(rds_codes),
        len(oracle_only),
        len(rds_only),
    )
    if oracle_only:
        logger.warning(
            "Subaward codes in Oracle but missing from archive.subaward "
            "({:,}): {}",
            len(oracle_only),
            oracle_only[:50],
        )
    if rds_only:
        logger.warning(
            "Subaward codes in archive.subaward but no longer in Oracle "
            "({:,}) - retained, never deleted: {}",
            len(rds_only),
            rds_only[:50],
        )

    return {
        "oracle_count": len(oracle_codes),
        "rds_count": len(rds_codes),
        "oracle_only": oracle_only,
        "rds_only": rds_only,
    }


def run_sync_all() -> dict[str, object]:
    """Full Oracle-to-Postgres Subaward sync: every SUBAWARD_CODE family,
    via the same idempotent per-family UPSERT transaction machinery as
    run_targeted_load (never TRUNCATE, never touches
    subaward_attachment_archive). Guarded by a non-blocking PostgreSQL
    advisory lock (SUBAWARD_SYNC_ADVISORY_LOCK_KEY) so at most one sync
    runs at a time; a concurrent invocation exits cleanly without doing
    any work rather than racing the one already running."""
    engine = create_postgres_engine()
    apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")

    lock_connection = engine.connect()
    try:
        if not _try_acquire_sync_lock(lock_connection):
            logger.warning(
                "Another Subaward sync already holds the advisory lock - "
                "exiting without doing any work"
            )
            return {"skipped": True, "reason": "lock_held"}

        subawards_spec = _dataset_by_key("subawards")
        all_codes = sorted(
            OracleDataSource(subawards_spec.oracle_path)
            .read()["subaward_code"]
            .dropna()
            .astype(str)
            .unique()
            .tolist()
        )
        logger.info(
            "--sync-all: {:,} Subaward families in Oracle", len(all_codes)
        )

        result = run_targeted_load(all_codes, engine=engine)
        result["reconciliation"] = reconcile_subaward_codes(engine)
        return result
    finally:
        try:
            _release_sync_lock(lock_connection)
        finally:
            lock_connection.close()


def run_targeted_load(
    target_codes: list[str],
    engine: Engine | None = None,
) -> dict[str, object]:
    """Loads exactly the given Subaward families (every version and all
    child rows sharing each SUBAWARD_CODE) via an idempotent per-row
    UPSERT (archive_etl.reference_data._upsert_rows), one Postgres
    transaction PER FAMILY - a bad row on one family is logged and
    skipped, every other family still loads. Mirrors
    load_negotiations_from_csv.py's run_full_load in shape, generalized
    over the DATASETS spec table instead of hardcoding five tables."""
    logger.info(
        "Reading {} targeted Subaward famil{} from Oracle",
        len(target_codes),
        "y" if len(target_codes) == 1 else "ies",
    )

    datasets = {
        spec.key: prepare_dataset(
            spec,
            OracleDataSource(spec.oracle_path).read_filtered(
                column="subaward_code",
                values=target_codes,
            ),
        )
        for spec in DATASETS
    }

    subawards = datasets["subawards"]
    validate_parent_relationships(subawards, datasets)

    logger.info(
        "Prepared targeted Subaward rows: {}",
        " ".join(
            f"{spec.key}={len(datasets[spec.key]):,}" for spec in DATASETS
        ),
    )

    if engine is None:
        engine = create_postgres_engine()
        apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")

    totals = {
        spec.table_name: {"inserted": 0, "updated": 0, "unchanged": 0}
        for spec in DATASETS
    }
    completed = 0
    failed_codes: list[str] = []

    for code in target_codes:
        family_datasets = {
            spec.key: datasets[spec.key][
                datasets[spec.key]["subaward_code"] == code
            ]
            for spec in DATASETS
        }

        try:
            with engine.begin() as connection:
                total_rows = sum(
                    len(dataframe) for dataframe in family_datasets.values()
                )
                load_id = create_load_run(connection, total_rows)

                for spec in DATASETS:
                    renamed = family_datasets[spec.key].rename(
                        columns=SOURCE_COLUMN_RENAMES
                    )
                    result = _upsert_rows(
                        connection,
                        table=spec.table_name,
                        pk_columns=[spec.primary_key],
                        columns=spec.archive_columns,
                        rows=renamed,
                        load_id=load_id,
                    )
                    for key in ("inserted", "updated", "unchanged"):
                        totals[spec.table_name][key] += result[key]

                mark_load_complete(connection, load_id, total_rows)
            completed += 1
        except Exception as error:
            failed_codes.append(code)
            logger.error(
                "Subaward family {} failed to load: {}",
                code,
                redact_error_message(str(error)),
            )

    logger.success(
        "Targeted Subaward load finished: {:,} requested, {:,} completed, "
        "{:,} failed",
        len(target_codes),
        completed,
        len(failed_codes),
    )
    for table_name, result in totals.items():
        logger.info(
            "TOTAL {:<35} inserted={:,} updated={:,} unchanged={:,}",
            table_name,
            result["inserted"],
            result["updated"],
            result["unchanged"],
        )
    if failed_codes:
        logger.warning(
            "Failed Subaward codes ({:,}): {}",
            len(failed_codes),
            failed_codes[:50],
        )

    return {
        "requested": len(target_codes),
        "completed": completed,
        "failed": len(failed_codes),
        "failed_codes": failed_codes,
        "totals": totals,
    }


def main() -> None:
    arguments = parse_args()

    if arguments.ecs:
        _run_ecs_setup()

    if arguments.reconcile_only:
        engine = create_postgres_engine()
        result = reconcile_subaward_codes(engine)
        if result["oracle_only"]:
            raise SystemExit(
                f"Reconciliation gap: {len(result['oracle_only'])} "
                "Subaward code(s) exist in Oracle but not "
                "archive.subaward"
            )
        return

    if arguments.sync_all:
        result = run_sync_all()
        if result.get("skipped"):
            return
        reconciliation = result["reconciliation"]
        if result["failed"] or reconciliation["oracle_only"]:
            raise SystemExit(
                f"--sync-all did not fully converge: {result['failed']} "
                f"family failure(s), {len(reconciliation['oracle_only'])} "
                "code(s) still missing from archive.subaward"
            )
        return

    target_codes = resolve_target_subaward_codes(arguments)
    if target_codes is not None:
        if not target_codes:
            logger.warning(
                "No target Subaward families resolved - nothing to load"
            )
            return
        run_targeted_load(target_codes)
        return

    logger.info("Reading Subaward data from Oracle")
    datasets = {
        spec.key: prepare_dataset(spec, read_oracle(spec))
        for spec in DATASETS
    }

    if arguments.limit is not None:
        datasets = {
            key: dataframe.head(arguments.limit)
            for key, dataframe in datasets.items()
        }
        logger.info(
            "Dry run (--limit {}): read {} - skipping validation and "
            "database write.",
            arguments.limit,
            " ".join(f"{key}={len(df):,}" for key, df in datasets.items()),
        )
        return

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
        PROJECT_ROOT / "database" / "migrations",
    )

    # The STARTED load_run row is committed in its own transaction, before
    # the risky work below begins - otherwise a failure would roll back
    # the STARTED row along with everything else, and mark_load_failed
    # would silently update zero rows, leaving no trace of the failure.
    with engine.begin() as connection:
        load_id = create_load_run(connection, total_rows)

    # Reachable only when arguments.full_refresh is True - parse_args()
    # requires an explicit --full-refresh, --sync-all, --reconcile-only, or
    # a targeted selector, and every non-full-refresh path above already
    # returned. There is no default/no-verb way to reach this TRUNCATE.
    logger.warning("--full-refresh: truncating and reloading every Subaward table")
    try:
        with engine.begin() as connection:
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
    except Exception as error:
        mark_load_failed(engine, load_id, str(error))
        logger.exception("Subaward load failed")
        raise


if __name__ == "__main__":
    main()
