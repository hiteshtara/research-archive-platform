package edu.bu.archive.adapter.out.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/*
 * Kuali Document Search - a fixed, five-branch UNION ALL across the
 * approved core business-record modules (Award, Proposal, Negotiation,
 * Subaward, IRB), per docs/architecture/KUALI_DOCUMENT_METRIC_INVESTIGATION.md.
 * Deliberately excludes the Award-nested transactional/financial
 * document tables (Budget, Time and Money, Pending Transaction, SAP
 * transmission) and all attachment tables - those are child artifacts
 * of a business record, not independent Kuali documents a user searches
 * for by document number.
 *
 * The five branches are always present in the SQL text itself (never
 * assembled from a user-supplied module name) - a module filter is
 * applied as an ordinary bound WHERE parameter against the already-fixed
 * union, never by selecting which branch to include or by interpolating
 * a table name. This is the "fixed union" design the investigation
 * recommended over a dynamic per-module query.
 */
@Repository
@Transactional(readOnly = true)
public class DocumentSearchRepository {

    private static final String DOCUMENTS_CTE = """
            WITH documents AS (
                SELECT
                    'AWARD' AS module,
                    av.workflow_document_number AS document_number,
                    av.award_number AS business_record_number,
                    av.title,
                    av.status_description AS status,
                    av.sequence_number::text AS version_or_sequence,
                    av.begin_date AS relevant_date,
                    av.award_id::text AS target_id
                FROM archive.award_version av
                WHERE av.workflow_document_number IS NOT NULL

                UNION ALL

                SELECT
                    'PROPOSAL',
                    pv.document_number,
                    pv.proposal_number,
                    pv.title,
                    pv.status_description,
                    pv.version_number::text,
                    pv.initial_start_date,
                    pv.proposal_number
                FROM archive.proposal_version pv
                WHERE pv.document_number IS NOT NULL

                UNION ALL

                SELECT
                    'NEGOTIATION',
                    n.document_number,
                    n.negotiation_id::text,
                    NULL,
                    n.negotiation_status_description,
                    NULL,
                    n.negotiation_start_date,
                    n.negotiation_id::text
                FROM archive.negotiation n
                WHERE n.document_number IS NOT NULL

                UNION ALL

                SELECT
                    'SUBAWARD',
                    s.document_number,
                    s.subaward_code,
                    s.title,
                    s.status_description,
                    s.sequence_number::text,
                    s.start_date,
                    s.subaward_id::text
                FROM archive.subaward s
                WHERE s.document_number IS NOT NULL

                UNION ALL

                SELECT
                    'IRB',
                    ipv.document_number,
                    ipv.protocol_number,
                    ipv.title,
                    ipv.protocol_status,
                    ipv.sequence_number::text,
                    ipv.received_date,
                    ipv.protocol_id::text
                FROM archive.irb_protocol_version ipv
                WHERE ipv.document_number IS NOT NULL
            )
            """;

    private static final String FILTER_WHERE = """
            WHERE (:documentNumber = '' OR document_number ILIKE :documentNumberPattern)
              AND (:module = '' OR module = :module)
              AND (:businessRecordNumber = '' OR business_record_number ILIKE :businessRecordNumberPattern)
              AND (:title = '' OR title ILIKE :titlePattern)
              AND (:status = '' OR status ILIKE :statusPattern)
            """;

    private final JdbcClient jdbc;

    public DocumentSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<DocumentSearchRow> search(
            String documentNumber,
            String documentNumberPattern,
            String module,
            String businessRecordNumber,
            String businessRecordNumberPattern,
            String title,
            String titlePattern,
            String status,
            String statusPattern,
            int limit,
            int offset
    ) {
        return jdbc.sql(
                        DOCUMENTS_CTE
                                + """
                                SELECT
                                    module,
                                    document_number AS documentNumber,
                                    business_record_number AS businessRecordNumber,
                                    title,
                                    status,
                                    version_or_sequence AS versionOrSequence,
                                    relevant_date AS relevantDate,
                                    target_id AS targetId
                                FROM documents
                                """
                                + FILTER_WHERE
                                + """
                                ORDER BY module, document_number
                                LIMIT :limit OFFSET :offset
                                """
                )
                .param("documentNumber", documentNumber)
                .param("documentNumberPattern", documentNumberPattern)
                .param("module", module)
                .param("businessRecordNumber", businessRecordNumber)
                .param("businessRecordNumberPattern", businessRecordNumberPattern)
                .param("title", title)
                .param("titlePattern", titlePattern)
                .param("status", status)
                .param("statusPattern", statusPattern)
                .param("limit", limit)
                .param("offset", offset)
                .query(DocumentSearchRow.class)
                .list();
    }

    public long count(
            String documentNumber,
            String documentNumberPattern,
            String module,
            String businessRecordNumber,
            String businessRecordNumberPattern,
            String title,
            String titlePattern,
            String status,
            String statusPattern
    ) {
        Long count = jdbc.sql(
                        DOCUMENTS_CTE
                                + "SELECT COUNT(*) FROM documents "
                                + FILTER_WHERE
                )
                .param("documentNumber", documentNumber)
                .param("documentNumberPattern", documentNumberPattern)
                .param("module", module)
                .param("businessRecordNumber", businessRecordNumber)
                .param("businessRecordNumberPattern", businessRecordNumberPattern)
                .param("title", title)
                .param("titlePattern", titlePattern)
                .param("status", status)
                .param("statusPattern", statusPattern)
                .query(Long.class)
                .single();

        return count == null ? 0L : count;
    }
}
