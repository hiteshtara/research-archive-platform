package edu.bu.archive.adapter.in.web.dto.negotiation;

import java.time.LocalDateTime;

/*
 * label/name come from a LEFT JOIN to archive.custom_attribute, which
 * is deliberately not a foreign key (see V064) - it's loaded
 * independently, so an id with no matching row is expected, not an
 * error. Callers should fall back label -> name -> "Custom Field {id}",
 * never silently drop the row.
 */
public record NegotiationCustomDataResponse(
        Long negotiationCustomDataId,
        Long negotiationId,
        String negotiationNumber,
        Long customAttributeId,
        String label,
        String name,
        String value,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
