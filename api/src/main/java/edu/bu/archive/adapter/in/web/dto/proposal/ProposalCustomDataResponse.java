package edu.bu.archive.adapter.in.web.dto.proposal;

import java.time.LocalDateTime;

/*
 * archive.proposal_custom_data LEFT JOINed to the shared
 * archive.custom_attribute reference table. label/name/dataType are
 * null when the lookup has no matching row (custom_attribute_id is
 * deliberately not a foreign key - see V064's migration comment) -
 * ProposalCustomDataSection.tsx falls back to name, then a synthetic
 * "Custom Field {id}" label, never rendering the bare numeric ID as
 * the sole visible text. value being null is a real, persisted blank
 * value, distinct from the attribute simply having no row at all
 * (which never appears in this list).
 */
public record ProposalCustomDataResponse(
        Long proposalCustomDataId,
        Long customAttributeId,
        String label,
        String name,
        String dataType,
        String groupName,
        String value,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser
) {
}
