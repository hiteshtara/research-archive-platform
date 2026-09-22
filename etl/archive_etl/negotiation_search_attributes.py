"""Rebuilds archive.negotiation_search_attribute.

Resolves each Negotiation's displayable/filterable attributes (Title,
Principal Investigator, Sponsor, Prime Sponsor, Lead Unit, Sponsor Award
ID) from whichever source actually holds them, and denormalizes the
result so the Negotiation list and count queries never need a correlated
LATERAL. See V080's migration header for the measured reason.

Reads only already-archived PostgreSQL tables - archive.negotiation,
negotiation_unassociated_detail, award_version, award_person, unit and
sponsor. It does not connect to Oracle, so it can be re-run at any time
without VPN or Oracle credentials, and it is purely derived: nothing
here is a new extraction.

Writes are INSERT ... ON CONFLICT DO UPDATE only. There is deliberately
no DELETE and no TRUNCATE anywhere in this module.
"""

from __future__ import annotations

import time
from typing import Any

from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection, Engine

# Attribute-source precedence, and why it is this way round.
#
# A Negotiation's attributes come from archive.negotiation_unassociated_
# detail when it is unassociated, and from the associated Award when its
# association type is 'Award'. Those two are mostly disjoint - of 10,775
# negotiations, 8,533 unassociated ones have a detail row and 2,202
# Award-associated ones have none.
#
# Exactly 21 Award-associated negotiations have BOTH. For those, the
# Award wins here, because that is what Kuali's own screen resolves for
# an Award-associated Negotiation; a detail row on such a record is a
# leftover from before it was associated. This affects 21 of 10,775
# records (0.19%) and is worth confirming against a Kuali screen for one
# of them before this ships.
#
# Subaward (16) and Institutional Proposal (3) associations have no
# detail row and no fallback implemented, so they resolve to NONE and
# stay blank - 19 records, 0.18%.
_REBUILD_SQL = """
INSERT INTO archive.negotiation_search_attribute (
    negotiation_id,
    attribute_source,
    title,
    principal_investigator_name,
    principal_investigator_person_id,
    sponsor_code,
    sponsor_name,
    prime_sponsor_code,
    prime_sponsor_name,
    lead_unit_number,
    lead_unit_name,
    sponsor_award_number,
    load_id
)
SELECT
    n.negotiation_id,
    CASE
        WHEN aw.award_id IS NOT NULL THEN 'AWARD'
        WHEN d.negotiation_id IS NOT NULL THEN 'UNASSOCIATED_DETAIL'
        ELSE 'NONE'
    END AS attribute_source,
    COALESCE(aw.title, d.title),
    COALESCE(apn.full_name, d.pi_name),
    COALESCE(apn.person_id, d.pi_person_id),
    COALESCE(aw.sponsor_code, d.sponsor_code),
    COALESCE(aw.sponsor_name, sp.sponsor_name),
    COALESCE(aw.prime_sponsor_code, d.prime_sponsor_code),
    COALESCE(aw.prime_sponsor_name, psp.sponsor_name),
    COALESCE(aw.lead_unit_number, d.lead_unit),
    COALESCE(aw.lead_unit_name, u.unit_name),
    COALESCE(aw.sponsor_award_number, d.sponsor_award_number),
    :load_id
FROM archive.negotiation n
LEFT JOIN archive.negotiation_unassociated_detail d
       ON d.negotiation_id = n.negotiation_id
-- Award-association fallback. ASSOCIATED_DOCUMENT_ID matches
-- AWARD.AWARD_NUMBER for 2,223 of 2,223 Award-associated negotiations
-- (100%, verified live). Highest award_id is the current row for the
-- family, matching how AwardArchiveRepository resolves one.
LEFT JOIN LATERAL (
    SELECT av.award_id, av.title, av.sponsor_code, av.sponsor_name,
           av.prime_sponsor_code, av.prime_sponsor_name,
           av.lead_unit_number, av.lead_unit_name, av.sponsor_award_number
    FROM archive.award_version av
    WHERE n.negotiation_association_type_description = 'Award'
      AND av.award_number = n.associated_document_id
    ORDER BY av.award_id DESC
    LIMIT 1
) aw ON TRUE
-- Same PI-selection rule the Award repository already uses.
LEFT JOIN LATERAL (
    SELECT ap.full_name, ap.person_id
    FROM archive.award_person ap
    WHERE ap.award_id = aw.award_id
    ORDER BY
        CASE WHEN UPPER(TRIM(ap.contact_role_code)) = 'PI' THEN 0 ELSE 1 END,
        ap.full_name NULLS LAST
    LIMIT 1
) apn ON TRUE
-- Reference-table resolution. Never derives a name from a number.
LEFT JOIN archive.unit u
       ON u.unit_number = d.lead_unit
LEFT JOIN archive.sponsor sp
       ON sp.sponsor_code = d.sponsor_code
LEFT JOIN archive.sponsor psp
       ON psp.sponsor_code = d.prime_sponsor_code
ON CONFLICT (negotiation_id) DO UPDATE SET
    attribute_source = EXCLUDED.attribute_source,
    title = EXCLUDED.title,
    principal_investigator_name = EXCLUDED.principal_investigator_name,
    principal_investigator_person_id =
        EXCLUDED.principal_investigator_person_id,
    sponsor_code = EXCLUDED.sponsor_code,
    sponsor_name = EXCLUDED.sponsor_name,
    prime_sponsor_code = EXCLUDED.prime_sponsor_code,
    prime_sponsor_name = EXCLUDED.prime_sponsor_name,
    lead_unit_number = EXCLUDED.lead_unit_number,
    lead_unit_name = EXCLUDED.lead_unit_name,
    sponsor_award_number = EXCLUDED.sponsor_award_number,
    loaded_at = CURRENT_TIMESTAMP,
    load_id = EXCLUDED.load_id
"""

_COVERAGE_SQL = """
SELECT
    COUNT(*)                                   AS negotiations,
    COUNT(principal_investigator_name)         AS with_principal_investigator,
    COUNT(sponsor_name)                        AS with_sponsor_name,
    COUNT(lead_unit_name)                      AS with_lead_unit_name,
    COUNT(title)                               AS with_title,
    COUNT(*) FILTER (WHERE attribute_source = 'AWARD')
                                               AS source_award,
    COUNT(*) FILTER (WHERE attribute_source = 'UNASSOCIATED_DETAIL')
                                               AS source_unassociated_detail,
    COUNT(*) FILTER (WHERE attribute_source = 'NONE')
                                               AS source_none
FROM archive.negotiation_search_attribute
"""


def rebuild_negotiation_search_attributes(
    connection: Connection, load_id: int
) -> dict[str, int]:
    """Upserts one resolved-attribute row per archived Negotiation."""
    result = connection.execute(text(_REBUILD_SQL), {"load_id": load_id})
    written = result.rowcount if result.rowcount is not None else 0
    coverage = connection.execute(text(_COVERAGE_SQL)).mappings().one()
    report = {"rows_written": written, **dict(coverage)}
    return report


def run_rebuild_negotiation_search_attributes(
    engine: Engine, *, dry_run: bool = False
) -> dict[str, Any]:
    """Entry point for --rebuild-negotiation-search-attributes.

    Idempotent: re-running it recomputes every row from the current
    archive contents. Combine with --dry-run to roll the transaction
    back and still see the coverage report.
    """
    started = time.perf_counter()
    report: dict[str, Any] = {}

    with engine.connect() as connection:
        transaction = connection.begin()
        try:
            load_id = connection.execute(
                text(
                    """
                    INSERT INTO archive.load_run (
                        domain, source_system, source_file_name,
                        rows_read, status
                    ) VALUES (
                        'NEGOTIATION_SEARCH_ATTRIBUTES', 'ARCHIVE',
                        'Derived from archived PostgreSQL tables',
                        0, 'STARTED'
                    )
                    RETURNING load_id
                    """
                )
            ).scalar_one()

            report.update(
                rebuild_negotiation_search_attributes(connection, load_id)
            )

            connection.execute(
                text(
                    """
                    UPDATE archive.load_run
                       SET status = 'LOADED',
                           rows_read = :rows,
                           completed_at = CURRENT_TIMESTAMP
                     WHERE load_id = :load_id
                    """
                ),
                {"load_id": load_id, "rows": report.get("rows_written", 0)},
            )
        except Exception:
            transaction.rollback()
            raise
        else:
            if dry_run:
                transaction.rollback()
            else:
                transaction.commit()

    report["dry_run"] = dry_run
    report["elapsed_ms"] = (time.perf_counter() - started) * 1000
    logger.bind(stage="rebuild_negotiation_search_attributes").info(
        "Negotiation search attributes rebuilt", **report
    )
    return report
