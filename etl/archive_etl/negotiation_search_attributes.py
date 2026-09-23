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
# Exactly 21 Award-associated negotiations have BOTH. For those the
# Award wins, for EVERY resolved attribute - title, PI, sponsor and lead
# unit alike. This is now verified, not assumed: the rule was checked
# against the Kuali UI on the only three records where the candidate
# rules disagree, and all three display the current ACTIVE Award's
# values rather than the detail row's.
#
#   1641  detail PI HARRISON W FARBER  -> Kuali shows ELIZABETH S KLINGS
#   2587  detail PI HARRISON W FARBER  -> Kuali shows ROBERT W SIMMS
#   2676  detail PI JANE E FOX         -> Kuali shows JORGE DELVA
#
# 1641 also rules out an "as of the negotiation date" rule: Farber WAS
# the Award PI during that 2015 negotiation, yet Kuali displays the
# current ACTIVE Award PI. The resolution is current-state, not
# historical.
#
# THE DETAIL ROWS ARE NOT STALE OR ERRONEOUS. They hold real, genuinely
# recorded data - for 2676 a real PI, lead unit and sponsor that differ
# from the Award's. Kuali simply does not use them as the displayed
# source once a Negotiation is Award-associated. Nothing here deletes
# them or treats them as suspect; they remain archived in full and are
# still the source for unassociated Negotiations.
#
# This affects 21 of 10,775 records (0.19%).
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
-- Award association. ASSOCIATED_DOCUMENT_ID matches
-- AWARD.AWARD_NUMBER for 2,223 of 2,223 Award-associated negotiations
-- (100%, verified live) - a family-level key with no version pointer,
-- so the version has to be chosen here.
--
-- WHICH VERSION: the CURRENT ACTIVE one. Confirmed against the Kuali UI
-- on the three records where the candidate rules disagree - 1641, 2587
-- and 2676 - all of which display the current ACTIVE Award's values.
--
-- This is deliberately NOT ordered by award_id alone, and deliberately
-- NOT is_primary_current:
--   * award_id DESC picks a CANCELED row for award 205270-00001
--     (award_id 3142987, sequence 11, CANCELED) over its ACTIVE row
--     (3142979, sequence 10). 230 of 40,732 Award families have a
--     highest award_id that is not the ACTIVE version.
--   * is_primary_current (V013) ranks sequence_number DESC ahead of
--     ACTIVE, so it selects that same CANCELED sequence 11.
-- Both would contradict the verified Kuali behaviour, so ACTIVE is
-- ranked first explicitly.
--
-- THE DIVERGENCE FROM is_primary_current IS INTENTIONAL AND
-- EVIDENCE-BASED, not an oversight, and must not be "tidied up" into a
-- shared helper:
--   * is_primary_current (V013) is an ARCHIVE convention. It answers
--     "which row represents this Award family in the archive" and ranks
--     sequence_number DESC ahead of ACTIVE.
--   * Negotiation attribute resolution needs KUALI ACTIVE semantics -
--     "which Award version does the Kuali Negotiation screen display" -
--     which was established by direct UI verification.
-- Those two questions have different answers for 230 Award families, so
-- this ordering stays explicit and local to this feature. Do not
-- substitute is_primary_current here, and do not change
-- is_primary_current globally to match this: other callers depend on
-- its existing semantics. sequence_number/award_id remain as
-- deterministic tie-breaks, and are the only ordering left for the one
-- associated family that has no ACTIVE version at all
-- (negotiation 1 -> award 200421-00001), which would otherwise resolve
-- to nothing.
LEFT JOIN LATERAL (
    SELECT av.award_id, av.title, av.sponsor_code, av.sponsor_name,
           av.prime_sponsor_code, av.prime_sponsor_name,
           av.lead_unit_number, av.lead_unit_name, av.sponsor_award_number
    FROM archive.award_version av
    WHERE n.negotiation_association_type_description = 'Award'
      AND av.award_number = n.associated_document_id
    ORDER BY
        CASE
            WHEN UPPER(TRIM(av.award_sequence_status)) = 'ACTIVE' THEN 0
            ELSE 1
        END,
        av.sequence_number DESC,
        av.award_id DESC
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
