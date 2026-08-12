package edu.bu.archive.adapter.out.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/*
 * Kuali Document Explorer - a fixed, four-module UNION ALL (Award,
 * Proposal, Negotiation, Subaward - IRB excluded per
 * docs/architecture/KUALI_DOCUMENT_EXPLORER_DESIGN.md §16 decision 7,
 * confirmed: zero rows in dev, no unit column, no denormalized person
 * name, no sponsor column on archive.irb_protocol_version). Extends
 * the same fixed-union architecture DocumentSearchRepository already
 * shipped - a richer sibling, not a database view, materialized view,
 * or external search service.
 *
 * Status normalization is a literal SQL CASE expression per module,
 * not computed in Java - every mapping mirrors exactly the confident
 * table in the design doc's §2; every native status NOT explicitly
 * listed there falls through to 'UNKNOWN', never guessed. Real ambiguous
 * values that map to UNKNOWN today: Award "PAFO/OSP (Closing)",
 * "Pre-Close", "Compliance Hold", "Spending Hold", "Pre-Award Billable",
 * "Pre-Award Not Billable", "Do Not Use This Status"; Negotiation
 * "On Hold", "Limited Issues".
 *
 * Unit resolution:
 *   - Award: lead_unit_number/lead_unit_name (1) on the row itself,
 *     plus archive.award_person_unit (many) for "any associated unit".
 *   - Proposal/Subaward: lead_unit_number/requisitioner_unit (1) on
 *     the row itself - no multi-unit table exists, so "any associated
 *     unit" is identical to "lead unit only" for these two modules
 *     (never fabricated).
 *   - Negotiation: no unit column on the main row at all. Resolved
 *     ONLY for association_type='AWD' negotiations whose
 *     associated_document_id joins to a real, currently-loaded
 *     archive.award_version row (verified: only ~4% of AWD-type
 *     negotiations resolve this way in dev - most do not, and are left
 *     NULL rather than guessed). This inherited unit is clearly
 *     Negotiation's own module, distinguished in the UI copy (§8 of
 *     the design), never presented as if it were Negotiation's own
 *     native data.
 *
 * Sponsor resolution for Subaward is via a VERIFIED join
 * (subaward -> subaward_funding -> award_version), never the sparsely
 * populated (5.3%) archive.subaward.award_sponsor_name column - see
 * the pre-implementation validation this repository's design was
 * approved against (27/513 Subawards have any funding-Award row at
 * all; 3/513 resolve to a verified sponsor; 0 resolve to more than one
 * distinct sponsor in the current dev data, though the query supports
 * it structurally via sponsorCount).
 */
@Repository
@Transactional(readOnly = true)
public class DocumentExplorerRepository {

    private static final String EXPLORER_CTE = """
            WITH award_pi AS (
                SELECT DISTINCT ON (ap.award_id)
                    ap.award_id, ap.award_person_id, ap.full_name, ap.contact_role_code
                FROM archive.award_person ap
                ORDER BY ap.award_id,
                    CASE WHEN UPPER(TRIM(ap.contact_role_code)) IN ('PI', 'MPI') THEN 0 ELSE 1 END,
                    ap.award_person_id
            ),
            award_unit_counts AS (
                SELECT award_id, COUNT(DISTINCT unit_number) AS unit_count
                FROM archive.award_person_unit
                GROUP BY award_id
            ),
            award_person_counts AS (
                SELECT award_id, COUNT(*) AS person_count
                FROM archive.award_person
                GROUP BY award_id
            ),
            award_any_units AS (
                SELECT av.workflow_document_number AS document_number, apu.unit_number
                FROM archive.award_person_unit apu
                JOIN archive.award_version av ON av.award_id = apu.award_id
                WHERE av.workflow_document_number IS NOT NULL
            ),
            subaward_contact_counts AS (
                SELECT subaward_id, COUNT(*) AS contact_count
                FROM archive.subaward_contact
                GROUP BY subaward_id
            ),
            subaward_sponsor AS (
                SELECT
                    s.subaward_id,
                    (ARRAY_AGG(DISTINCT av.sponsor_code) FILTER (WHERE av.sponsor_code IS NOT NULL))[1] AS sponsor_code,
                    (ARRAY_AGG(DISTINCT av.sponsor_name) FILTER (WHERE av.sponsor_name IS NOT NULL))[1] AS sponsor_name,
                    COUNT(DISTINCT av.sponsor_code) FILTER (WHERE av.sponsor_code IS NOT NULL) AS sponsor_count
                FROM archive.subaward s
                JOIN archive.subaward_funding sf ON sf.subaward_id = s.subaward_id
                JOIN archive.award_version av ON av.award_number = sf.award_number
                    AND av.is_primary_current = TRUE
                WHERE av.sponsor_code IS NOT NULL
                GROUP BY s.subaward_id
            ),
            negotiation_associated_award AS (
                SELECT n.negotiation_id, av.lead_unit_number, av.lead_unit_name
                FROM archive.negotiation n
                JOIN archive.award_version av ON av.award_number = n.associated_document_id
                    AND av.is_primary_current = TRUE
                WHERE n.negotiation_association_type_code = 'AWD'
            ),
            documents AS (
                SELECT
                    'AWARD' AS module,
                    av.workflow_document_number AS document_number,
                    av.award_number AS business_record_number,
                    av.title,
                    CASE av.status_description
                        WHEN 'Approved Award' THEN 'ACTIVE'
                        WHEN 'Closed' THEN 'CLOSED'
                        WHEN 'Cancelled' THEN 'CANCELLED'
                        ELSE 'UNKNOWN'
                    END AS normalized_status,
                    av.status_code AS native_status_code,
                    av.status_description AS native_status_description,
                    av.sequence_number::text AS version_or_sequence,
                    av.lead_unit_number,
                    av.lead_unit_name,
                    pi.award_person_id::text AS primary_person_id,
                    pi.full_name AS primary_person_name,
                    pi.contact_role_code AS primary_person_role,
                    av.sponsor_code,
                    av.sponsor_name,
                    NULL::text AS subrecipient_organization_id,
                    NULL::text AS subrecipient_organization_name,
                    av.begin_date AS document_date,
                    av.award_id::text AS target_id,
                    COALESCE(auc.unit_count, 0) AS unit_count,
                    COALESCE(apc.person_count, 0) AS person_count,
                    0 AS sponsor_count
                FROM archive.award_version av
                LEFT JOIN award_pi pi ON pi.award_id = av.award_id
                LEFT JOIN award_unit_counts auc ON auc.award_id = av.award_id
                LEFT JOIN award_person_counts apc ON apc.award_id = av.award_id
                WHERE av.workflow_document_number IS NOT NULL

                UNION ALL

                SELECT
                    'PROPOSAL',
                    pv.document_number,
                    pv.proposal_number,
                    pv.title,
                    CASE pv.status_description
                        WHEN 'Pending' THEN 'PENDING'
                        WHEN 'Pending-Revised' THEN 'PENDING'
                        WHEN 'Funded' THEN 'ACTIVE'
                        WHEN 'Not Funded' THEN 'ARCHIVED'
                        WHEN 'Withdrawn' THEN 'ARCHIVED'
                        WHEN 'Deactivated Record' THEN 'ARCHIVED'
                        ELSE 'UNKNOWN'
                    END,
                    pv.status_code::text,
                    pv.status_description,
                    pv.version_number::text,
                    pv.lead_unit_number,
                    pv.lead_unit_name,
                    pv.principal_investigator_id,
                    pv.principal_investigator_name,
                    CASE WHEN pv.principal_investigator_name IS NOT NULL THEN 'Principal Investigator' ELSE NULL END,
                    pv.sponsor_code,
                    pv.sponsor_name,
                    NULL::text,
                    NULL::text,
                    pv.initial_start_date,
                    pv.proposal_number,
                    0,
                    CASE WHEN pv.principal_investigator_name IS NOT NULL THEN 1 ELSE 0 END,
                    0
                FROM archive.proposal_version pv
                WHERE pv.document_number IS NOT NULL

                UNION ALL

                SELECT
                    'NEGOTIATION',
                    n.document_number,
                    n.negotiation_id::text,
                    NULL::text,
                    CASE n.negotiation_status_description
                        WHEN 'Fully Executed' THEN 'ACTIVE'
                        WHEN 'Abandoned' THEN 'CANCELLED'
                        WHEN 'In Negotiation' THEN 'PENDING'
                        WHEN 'Signature In Process' THEN 'PENDING'
                        WHEN 'Under Review' THEN 'PENDING'
                        WHEN 'Request Acknowledged/Docs Req.' THEN 'PENDING'
                        ELSE 'UNKNOWN'
                    END,
                    n.negotiation_status_code::text,
                    n.negotiation_status_description,
                    NULL::text,
                    naa.lead_unit_number,
                    naa.lead_unit_name,
                    n.negotiator_person_id,
                    n.negotiator_full_name,
                    'Negotiator',
                    NULL::text,
                    NULL::text,
                    NULL::text,
                    NULL::text,
                    n.negotiation_start_date,
                    n.negotiation_id::text,
                    0,
                    1,
                    0
                FROM archive.negotiation n
                LEFT JOIN negotiation_associated_award naa ON naa.negotiation_id = n.negotiation_id
                WHERE n.document_number IS NOT NULL

                UNION ALL

                SELECT
                    'SUBAWARD',
                    s.document_number,
                    s.subaward_code,
                    s.title,
                    CASE s.status_description
                        WHEN '07. Executed' THEN 'ACTIVE'
                        WHEN '10. Cancelled' THEN 'CANCELLED'
                        WHEN '08. Closed' THEN 'CLOSED'
                        WHEN '01. RA Review' THEN 'PENDING'
                        WHEN '02. Subaward RA initial review' THEN 'PENDING'
                        WHEN '03. Pending new or updated FRN' THEN 'PENDING'
                        WHEN '04. PI/DA' THEN 'PENDING'
                        WHEN '05. Sent to subrecipient' THEN 'PENDING'
                        ELSE 'UNKNOWN'
                    END,
                    s.status_code::text,
                    s.status_description,
                    s.sequence_number::text,
                    s.requisitioner_unit,
                    NULL::text,
                    s.site_investigator::text,
                    NULL::text,
                    CASE WHEN s.site_investigator IS NOT NULL THEN 'Site Investigator' ELSE NULL END,
                    ss.sponsor_code,
                    ss.sponsor_name,
                    s.organization_id,
                    NULL::text,
                    s.start_date,
                    s.subaward_id::text,
                    0,
                    (CASE WHEN s.site_investigator IS NOT NULL THEN 1 ELSE 0 END) + COALESCE(scc.contact_count, 0),
                    COALESCE(ss.sponsor_count, 0)
                FROM archive.subaward s
                LEFT JOIN subaward_sponsor ss ON ss.subaward_id = s.subaward_id
                LEFT JOIN subaward_contact_counts scc ON scc.subaward_id = s.subaward_id
                WHERE s.document_number IS NOT NULL
            )
            """;

    /*
     * Default-page fast path (no filters set): the heavy CTE above pays
     * ~13s per call because award_pi/award_unit_counts/award_person_counts
     * fully scan+aggregate archive.award_person (325K rows) and
     * archive.award_person_unit (334K rows) for every one of the
     * 267,386 Award rows, on every request, before any LIMIT/OFFSET can
     * apply - confirmed via EXPLAIN (ANALYZE, BUFFERS) during the
     * 2026-08-12 performance investigation (see
     * docs/architecture/KUALI_DOCUMENT_EXPLORER_PERFORMANCE.md). This
     * path is used ONLY when DocumentExplorerService.isUnfiltered()
     * confirms every filter field is at its default - the moment any
     * filter is set, the service falls back to search()/count()/
     * moduleFacets() above unchanged, so filtered correctness (and every
     * existing test for it) is untouched.
     *
     * Two-stage query: (1) a "light" union with only the columns
     * directly available on each module's own row (no award_person/
     * award_person_unit aggregation), ordered and paginated down to the
     * requested page; (2) Award-only enrichment (primary person, unit
     * count, person count) scoped via WHERE award_id IN (<= size Award
     * ids from the page)) - at most `size` rows, never all 267,386.
     * Proposal/Negotiation/Subaward never needed that aggregation to
     * begin with (their "primary person" is a single denormalized
     * column, not a DISTINCT ON pick across many rows), so they need no
     * second stage at all - the light union already carries their real,
     * final values straight through the COALESCE below.
     *
     * source_record_id is a NEW, request-internal identity column (not
     * exposed in DocumentExplorerRow) - the true per-row key
     * (award_id / proposal_id:version_number / negotiation_id /
     * subaward_id), appended as the final ORDER BY tie-breaker after
     * document_number. document_number is NOT globally unique (e.g.
     * AWARD/141590 legitimately has 8 archived rows sharing one
     * workflow document number) - without this tie-breaker, LIMIT/OFFSET
     * pagination over a non-unique sort key is not guaranteed stable
     * across requests and could silently drop or duplicate rows across
     * adjacent pages.
     */
    private static final String LIGHT_SUPPORTING_CTE = """
            WITH subaward_contact_counts AS (
                SELECT subaward_id, COUNT(*) AS contact_count
                FROM archive.subaward_contact
                GROUP BY subaward_id
            ),
            subaward_sponsor AS (
                SELECT
                    s.subaward_id,
                    (ARRAY_AGG(DISTINCT av.sponsor_code) FILTER (WHERE av.sponsor_code IS NOT NULL))[1] AS sponsor_code,
                    (ARRAY_AGG(DISTINCT av.sponsor_name) FILTER (WHERE av.sponsor_name IS NOT NULL))[1] AS sponsor_name,
                    COUNT(DISTINCT av.sponsor_code) FILTER (WHERE av.sponsor_code IS NOT NULL) AS sponsor_count
                FROM archive.subaward s
                JOIN archive.subaward_funding sf ON sf.subaward_id = s.subaward_id
                JOIN archive.award_version av ON av.award_number = sf.award_number
                    AND av.is_primary_current = TRUE
                WHERE av.sponsor_code IS NOT NULL
                GROUP BY s.subaward_id
            ),
            negotiation_associated_award AS (
                SELECT n.negotiation_id, av.lead_unit_number, av.lead_unit_name
                FROM archive.negotiation n
                JOIN archive.award_version av ON av.award_number = n.associated_document_id
                    AND av.is_primary_current = TRUE
                WHERE n.negotiation_association_type_code = 'AWD'
            ),
            documents_light AS (
                SELECT
                    'AWARD' AS module,
                    av.workflow_document_number AS document_number,
                    av.award_number AS business_record_number,
                    av.title,
                    CASE av.status_description
                        WHEN 'Approved Award' THEN 'ACTIVE'
                        WHEN 'Closed' THEN 'CLOSED'
                        WHEN 'Cancelled' THEN 'CANCELLED'
                        ELSE 'UNKNOWN'
                    END AS normalized_status,
                    av.status_code AS native_status_code,
                    av.status_description AS native_status_description,
                    av.sequence_number::text AS version_or_sequence,
                    av.lead_unit_number,
                    av.lead_unit_name,
                    NULL::text AS primary_person_id,
                    NULL::text AS primary_person_name,
                    NULL::text AS primary_person_role,
                    av.sponsor_code,
                    av.sponsor_name,
                    NULL::text AS subrecipient_organization_id,
                    NULL::text AS subrecipient_organization_name,
                    av.begin_date AS document_date,
                    av.award_id::text AS target_id,
                    0 AS unit_count,
                    0 AS person_count,
                    0 AS sponsor_count,
                    av.award_id::text AS source_record_id
                FROM archive.award_version av
                WHERE av.workflow_document_number IS NOT NULL

                UNION ALL

                SELECT
                    'PROPOSAL',
                    pv.document_number,
                    pv.proposal_number,
                    pv.title,
                    CASE pv.status_description
                        WHEN 'Pending' THEN 'PENDING'
                        WHEN 'Pending-Revised' THEN 'PENDING'
                        WHEN 'Funded' THEN 'ACTIVE'
                        WHEN 'Not Funded' THEN 'ARCHIVED'
                        WHEN 'Withdrawn' THEN 'ARCHIVED'
                        WHEN 'Deactivated Record' THEN 'ARCHIVED'
                        ELSE 'UNKNOWN'
                    END,
                    pv.status_code::text,
                    pv.status_description,
                    pv.version_number::text,
                    pv.lead_unit_number,
                    pv.lead_unit_name,
                    pv.principal_investigator_id,
                    pv.principal_investigator_name,
                    CASE WHEN pv.principal_investigator_name IS NOT NULL THEN 'Principal Investigator' ELSE NULL END,
                    pv.sponsor_code,
                    pv.sponsor_name,
                    NULL::text,
                    NULL::text,
                    pv.initial_start_date,
                    pv.proposal_number,
                    0,
                    CASE WHEN pv.principal_investigator_name IS NOT NULL THEN 1 ELSE 0 END,
                    0,
                    pv.proposal_id::text || ':' || pv.version_number::text
                FROM archive.proposal_version pv
                WHERE pv.document_number IS NOT NULL

                UNION ALL

                SELECT
                    'NEGOTIATION',
                    n.document_number,
                    n.negotiation_id::text,
                    NULL::text,
                    CASE n.negotiation_status_description
                        WHEN 'Fully Executed' THEN 'ACTIVE'
                        WHEN 'Abandoned' THEN 'CANCELLED'
                        WHEN 'In Negotiation' THEN 'PENDING'
                        WHEN 'Signature In Process' THEN 'PENDING'
                        WHEN 'Under Review' THEN 'PENDING'
                        WHEN 'Request Acknowledged/Docs Req.' THEN 'PENDING'
                        ELSE 'UNKNOWN'
                    END,
                    n.negotiation_status_code::text,
                    n.negotiation_status_description,
                    NULL::text,
                    naa.lead_unit_number,
                    naa.lead_unit_name,
                    n.negotiator_person_id,
                    n.negotiator_full_name,
                    'Negotiator',
                    NULL::text,
                    NULL::text,
                    NULL::text,
                    NULL::text,
                    n.negotiation_start_date,
                    n.negotiation_id::text,
                    0,
                    1,
                    0,
                    n.negotiation_id::text
                FROM archive.negotiation n
                LEFT JOIN negotiation_associated_award naa ON naa.negotiation_id = n.negotiation_id
                WHERE n.document_number IS NOT NULL

                UNION ALL

                SELECT
                    'SUBAWARD',
                    s.document_number,
                    s.subaward_code,
                    s.title,
                    CASE s.status_description
                        WHEN '07. Executed' THEN 'ACTIVE'
                        WHEN '10. Cancelled' THEN 'CANCELLED'
                        WHEN '08. Closed' THEN 'CLOSED'
                        WHEN '01. RA Review' THEN 'PENDING'
                        WHEN '02. Subaward RA initial review' THEN 'PENDING'
                        WHEN '03. Pending new or updated FRN' THEN 'PENDING'
                        WHEN '04. PI/DA' THEN 'PENDING'
                        WHEN '05. Sent to subrecipient' THEN 'PENDING'
                        ELSE 'UNKNOWN'
                    END,
                    s.status_code::text,
                    s.status_description,
                    s.sequence_number::text,
                    s.requisitioner_unit,
                    NULL::text,
                    s.site_investigator::text,
                    NULL::text,
                    CASE WHEN s.site_investigator IS NOT NULL THEN 'Site Investigator' ELSE NULL END,
                    ss.sponsor_code,
                    ss.sponsor_name,
                    s.organization_id,
                    NULL::text,
                    s.start_date,
                    s.subaward_id::text,
                    0,
                    (CASE WHEN s.site_investigator IS NOT NULL THEN 1 ELSE 0 END) + COALESCE(scc.contact_count, 0),
                    COALESCE(ss.sponsor_count, 0),
                    s.subaward_id::text
                FROM archive.subaward s
                LEFT JOIN subaward_sponsor ss ON ss.subaward_id = s.subaward_id
                LEFT JOIN subaward_contact_counts scc ON scc.subaward_id = s.subaward_id
                WHERE s.document_number IS NOT NULL
            )
            """;

    private static final String AWARD_ENRICHMENT_CTE = """
            paginated_award_ids AS (
                SELECT DISTINCT target_id::bigint AS award_id FROM paginated WHERE module = 'AWARD'
            ),
            award_pi AS (
                SELECT DISTINCT ON (ap.award_id)
                    ap.award_id, ap.award_person_id, ap.full_name, ap.contact_role_code
                FROM archive.award_person ap
                WHERE ap.award_id IN (SELECT award_id FROM paginated_award_ids)
                ORDER BY ap.award_id,
                    CASE WHEN UPPER(TRIM(ap.contact_role_code)) IN ('PI', 'MPI') THEN 0 ELSE 1 END,
                    ap.award_person_id
            ),
            award_unit_counts AS (
                SELECT award_id, COUNT(DISTINCT unit_number) AS unit_count
                FROM archive.award_person_unit
                WHERE award_id IN (SELECT award_id FROM paginated_award_ids)
                GROUP BY award_id
            ),
            award_person_counts AS (
                SELECT award_id, COUNT(*) AS person_count
                FROM archive.award_person
                WHERE award_id IN (SELECT award_id FROM paginated_award_ids)
                GROUP BY award_id
            )
            """;

    private static final String DEFAULT_PAGE_SELECT = """
            SELECT
                p.module, p.document_number AS documentNumber,
                p.business_record_number AS businessRecordNumber, p.title,
                p.normalized_status AS normalizedStatus,
                p.native_status_code AS nativeStatusCode,
                p.native_status_description AS nativeStatusDescription,
                p.version_or_sequence AS versionOrSequence,
                p.lead_unit_number AS leadUnitNumber, p.lead_unit_name AS leadUnitName,
                COALESCE(pi.award_person_id::text, p.primary_person_id) AS primaryPersonId,
                COALESCE(pi.full_name, p.primary_person_name) AS primaryPersonName,
                COALESCE(pi.contact_role_code, p.primary_person_role) AS primaryPersonRole,
                p.sponsor_code AS sponsorCode, p.sponsor_name AS sponsorName,
                p.subrecipient_organization_id AS subrecipientOrganizationId,
                p.subrecipient_organization_name AS subrecipientOrganizationName,
                p.document_date AS documentDate, p.target_id AS targetId,
                COALESCE(auc.unit_count, p.unit_count) AS unitCount,
                COALESCE(apc.person_count, p.person_count) AS personCount,
                p.sponsor_count AS sponsorCount
            FROM paginated p
            LEFT JOIN award_pi pi ON p.module = 'AWARD' AND pi.award_id = p.target_id::bigint
            LEFT JOIN award_unit_counts auc ON p.module = 'AWARD' AND auc.award_id = p.target_id::bigint
            LEFT JOIN award_person_counts apc ON p.module = 'AWARD' AND apc.award_id = p.target_id::bigint
            """;

    private static final String DEFAULT_COUNT_SQL = """
            SELECT
                (SELECT COUNT(*) FROM archive.award_version WHERE workflow_document_number IS NOT NULL)
                + (SELECT COUNT(*) FROM archive.proposal_version WHERE document_number IS NOT NULL)
                + (SELECT COUNT(*) FROM archive.negotiation WHERE document_number IS NOT NULL)
                + (SELECT COUNT(*) FROM archive.subaward WHERE document_number IS NOT NULL)
            """;

    private static final String DEFAULT_FACETS_SQL = """
            SELECT 'AWARD' AS module, COUNT(*) AS n FROM archive.award_version WHERE workflow_document_number IS NOT NULL
            UNION ALL
            SELECT 'PROPOSAL', COUNT(*) FROM archive.proposal_version WHERE document_number IS NOT NULL
            UNION ALL
            SELECT 'NEGOTIATION', COUNT(*) FROM archive.negotiation WHERE document_number IS NOT NULL
            UNION ALL
            SELECT 'SUBAWARD', COUNT(*) FROM archive.subaward WHERE document_number IS NOT NULL
            """;

    private static final String FILTER_WHERE = """
            WHERE (:documentNumber = '' OR document_number ILIKE :documentNumberPattern)
              AND (:businessRecordNumber = '' OR business_record_number ILIKE :businessRecordNumberPattern)
              AND (:query = '' OR document_number ILIKE :queryPattern OR title ILIKE :queryPattern OR business_record_number ILIKE :queryPattern)
              AND (:module = '' OR module = :module)
              AND (:normalizedStatus = '' OR normalized_status = :normalizedStatus)
              AND (:nativeStatus = '' OR native_status_description = :nativeStatus)
              AND (
                    :unitNumber = ''
                    OR lead_unit_number = :unitNumber
                    OR (
                        :includeAnyUnit = TRUE
                        AND EXISTS (
                            SELECT 1 FROM award_any_units dau
                            WHERE dau.document_number = documents.document_number
                              AND dau.unit_number = :unitNumber
                        )
                    )
              )
              AND (:personId = '' OR primary_person_id = :personId)
              AND (:personName = '' OR primary_person_name ILIKE :personNamePattern)
              AND (:personRole = '' OR primary_person_role ILIKE :personRolePattern)
              AND (:piOnly = FALSE OR module <> 'AWARD' OR primary_person_role IN ('PI', 'MPI'))
              AND (:sponsorCode = '' OR sponsor_code = :sponsorCode)
              AND (:subrecipientOrganizationId = '' OR subrecipient_organization_id ILIKE :subrecipientOrganizationPattern)
              AND (CAST(:dateFrom AS date) IS NULL OR document_date >= CAST(:dateFrom AS date))
              AND (CAST(:dateTo AS date) IS NULL OR document_date <= CAST(:dateTo AS date))
            """;

    private static final String SELECT_LIST = """
            SELECT
                module, document_number AS documentNumber,
                business_record_number AS businessRecordNumber, title,
                normalized_status AS normalizedStatus,
                native_status_code AS nativeStatusCode,
                native_status_description AS nativeStatusDescription,
                version_or_sequence AS versionOrSequence,
                lead_unit_number AS leadUnitNumber, lead_unit_name AS leadUnitName,
                primary_person_id AS primaryPersonId, primary_person_name AS primaryPersonName,
                primary_person_role AS primaryPersonRole,
                sponsor_code AS sponsorCode, sponsor_name AS sponsorName,
                subrecipient_organization_id AS subrecipientOrganizationId,
                subrecipient_organization_name AS subrecipientOrganizationName,
                document_date AS documentDate, target_id AS targetId,
                unit_count AS unitCount, person_count AS personCount, sponsor_count AS sponsorCount
            FROM documents
            """;

    private final JdbcClient jdbc;

    public DocumentExplorerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<DocumentExplorerRow> search(DocumentExplorerFilter filter, int limit, int offset) {
        return bind(
                jdbc.sql(EXPLORER_CTE + SELECT_LIST + FILTER_WHERE + orderByClause(filter.sort())
                        + " LIMIT :limit OFFSET :offset"),
                filter
        )
                .param("limit", limit)
                .param("offset", offset)
                .query(DocumentExplorerRow.class)
                .list();
    }

    public long count(DocumentExplorerFilter filter) {
        Long count = bind(
                jdbc.sql(EXPLORER_CTE + "SELECT COUNT(*) FROM documents " + FILTER_WHERE),
                filter
        )
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    public List<ModuleFacetRow> moduleFacets(DocumentExplorerFilter filter) {
        return bind(
                jdbc.sql(EXPLORER_CTE
                        + "SELECT module, COUNT(*) AS n FROM documents "
                        + FILTER_WHERE
                        + " GROUP BY module"),
                filter
        )
                .query(ModuleFacetRow.class)
                .list();
    }

    private String orderByClause(String sort) {
        // Fixed allowlist only - never a raw client-supplied column
        // name, per the security requirement in the design doc's §7/§13.
        return switch (sort) {
            case "title" -> " ORDER BY title NULLS LAST, document_number";
            case "documentDate" -> " ORDER BY document_date DESC NULLS LAST, document_number";
            case "module" -> " ORDER BY module, document_number";
            default -> " ORDER BY document_number, module";
        };
    }

    // --- Default-page fast path (DocumentExplorerService.isUnfiltered()
    // only) - see the LIGHT_SUPPORTING_CTE javadoc-style comment above
    // for the full rationale. search()/count()/moduleFacets() above are
    // never called for this case and remain completely unused/unchanged
    // by it. ---

    public long countDefault() {
        Long count = jdbc.sql(DEFAULT_COUNT_SQL).query(Long.class).single();
        return count == null ? 0L : count;
    }

    public List<ModuleFacetRow> moduleFacetsDefault() {
        return jdbc.sql(DEFAULT_FACETS_SQL).query(ModuleFacetRow.class).list();
    }

    public List<DocumentExplorerRow> searchDefaultPage(int limit, int offset, String sort) {
        String sql = LIGHT_SUPPORTING_CTE
                + ", paginated AS ( SELECT * FROM documents_light"
                + lightOrderByClause(sort, "")
                + " LIMIT :limit OFFSET :offset ), "
                + AWARD_ENRICHMENT_CTE
                + DEFAULT_PAGE_SELECT
                + lightOrderByClause(sort, "p.");
        return jdbc.sql(sql)
                .param("limit", limit)
                .param("offset", offset)
                .query(DocumentExplorerRow.class)
                .list();
    }

    private String lightOrderByClause(String sort, String prefix) {
        // Same fixed allowlist as orderByClause(), with source_record_id
        // (the true per-row identity - never document_number alone,
        // which is not globally unique) appended as a final tie-breaker
        // so LIMIT/OFFSET pagination is deterministic across requests.
        return switch (sort) {
            case "title" -> " ORDER BY " + prefix + "title NULLS LAST, "
                    + prefix + "document_number, " + prefix + "source_record_id";
            case "documentDate" -> " ORDER BY " + prefix + "document_date DESC NULLS LAST, "
                    + prefix + "document_number, " + prefix + "source_record_id";
            case "module" -> " ORDER BY " + prefix + "module, "
                    + prefix + "document_number, " + prefix + "source_record_id";
            default -> " ORDER BY " + prefix + "document_number, "
                    + prefix + "module, " + prefix + "source_record_id";
        };
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, DocumentExplorerFilter filter) {
        return statement
                .param("documentNumber", filter.documentNumber())
                .param("documentNumberPattern", "%" + filter.documentNumber() + "%")
                .param("businessRecordNumber", filter.businessRecordNumber())
                .param("businessRecordNumberPattern", "%" + filter.businessRecordNumber() + "%")
                .param("query", filter.query())
                .param("queryPattern", "%" + filter.query() + "%")
                .param("module", filter.module())
                .param("normalizedStatus", filter.normalizedStatus())
                .param("nativeStatus", filter.nativeStatus())
                .param("unitNumber", filter.unitNumber())
                .param("includeAnyUnit", filter.includeAnyUnit())
                .param("personId", filter.personId())
                .param("personName", filter.personName())
                .param("personNamePattern", "%" + filter.personName() + "%")
                .param("personRole", filter.personRole())
                .param("personRolePattern", "%" + filter.personRole() + "%")
                .param("piOnly", filter.piOnly())
                .param("sponsorCode", filter.sponsorCode())
                .param("subrecipientOrganizationId", filter.subrecipientOrganizationId())
                .param("subrecipientOrganizationPattern", "%" + filter.subrecipientOrganizationId() + "%")
                .param("dateFrom", filter.dateFrom())
                .param("dateTo", filter.dateTo());
    }

    public record ModuleFacetRow(String module, long n) {
    }
}
