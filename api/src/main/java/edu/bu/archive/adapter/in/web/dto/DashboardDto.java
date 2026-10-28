package edu.bu.archive.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Dashboard counts.
 *
 * <p>The four IRB figures are {@link Long} rather than {@code long} and the
 * record is {@code NON_NULL}: when record-level enforcement is on they are
 * null and are therefore ABSENT from the JSON entirely (decision D9). Absent
 * is deliberate - a zero would be a false statement, read as "the archive
 * holds no IRB protocols" rather than "this figure is not available to you".
 *
 * <p>The non-IRB figures are primitives and are always present. {@code
 * documents} stays a single number; when IRB is excluded the IRB branch is
 * simply not part of the union, so the tally carries no IRB subtotal.
 *
 * <p>With enforcement off - today's deployed configuration - every field is
 * populated exactly as before.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardDto(
        Long irb,
        Long submissions,
        Long fundingRecords,
        Long timelineEvents,
        long awards,
        long awardHistoryRecords,
        long proposals,
        long proposalHistoryRecords,
        long negotiations,
        long subawards,
        long documents
) {
}
