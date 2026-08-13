package edu.bu.archive.adapter.in.web.dto.award;

/*
 * LEFT JOINed to the shared archive.sponsor_term/archive.sponsor_term_type
 * reference tables (V074) for a readable code + description + Kuali
 * category, mirroring AwardCustomDataResponse's LEFT JOIN pattern.
 * Neither lookup has a foreign key from award_sponsor_term (V040's own
 * migration comment), so a sponsorTermId with no matching lookup row
 * still comes back with sponsorTermCode/description/sponsorTermTypeCode/
 * categoryDescription all null rather than being dropped -
 * AwardTermsSection.tsx falls back to the raw sponsorTermId in that case.
 * sponsorTermId (SPONSOR_TERM's own surrogate PK) and sponsorTermCode
 * (the human-readable code Kuali's UI actually displays) are
 * deliberately different values - see AWARD_TERMS_DESIGN.md and the
 * live-verified award_id 2727052 fixture (sponsorTermId 370 ->
 * sponsorTermCode "64").
 */
public record AwardSponsorTermResponse(
        Long awardSponsorTermId,
        Long sponsorTermId,
        String sponsorTermCode,
        String description,
        String sponsorTermTypeCode,
        String categoryDescription
) {
}
