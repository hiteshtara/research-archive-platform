package edu.bu.archive.adapter.in.web.dto.negotiation;

import java.time.LocalDateTime;

public record NegotiationUnassociatedDetailResponse(
        Long negotiationUnassocDetailId,
        Long negotiationId,
        String title,
        String piPersonId,
        String piRolodexId,
        String leadUnit,
        String sponsorCode,
        String piName,
        String primeSponsorCode,
        String sponsorAwardNumber,
        String contactAdminPersonId,
        String subawardOrg,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
