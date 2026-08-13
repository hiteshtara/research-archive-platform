package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDateTime;

/*
 * archive.award_custom_data LEFT JOINed to the shared
 * archive.custom_attribute reference table (see database migration
 * V064 and ProposalV1Repository.findCustomDataRows, the reference
 * pattern this mirrors). label/name/dataType/groupName are null when
 * the lookup has no matching row - custom_attribute_id is deliberately
 * not a foreign key (V038's own migration comment), so a missing
 * lookup surfaces as a null label, never a load or query failure.
 * AwardCustomDataSection.tsx falls back to name, then a synthetic
 * "Custom Field {id}" label, never rendering the bare numeric ID as
 * the sole visible text. value being null is a real, persisted blank
 * value, distinct from the attribute simply having no row at all
 * (which never appears in this list).
 */
public record AwardCustomDataResponse(
        Long awardCustomDataId,
        Long awardId,
        String awardNumber,
        Integer sequenceNumber,
        Long customAttributeId,
        String label,
        String name,
        String dataType,
        String groupName,
        String value,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
