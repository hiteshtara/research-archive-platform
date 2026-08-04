package edu.bu.archive.adapter.in.web.dto.award;

/*
 * A minimal, resolve-only response: given a stable, human-meaningful
 * awardNumber, returns the current version's internal awardId so a
 * caller (e.g. the Institutional Proposal "Funded Awards" section) can
 * navigate to /awards/{awardId} without ever having received or
 * displayed that internal identifier itself - the resolution happens
 * here, server-side, at click-time, not baked into another domain's
 * response payload.
 */
public record AwardIdentifierResponse(
        Long awardId,
        String awardNumber,
        Integer sequenceNumber
) {
}
