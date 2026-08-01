package edu.bu.archive.adapter.in.web.dto.award;

/*
 * sponsorTermId is a bare FK to Oracle's (unarchived) SPONSOR_TERM
 * lookup table - no description text exists anywhere in this archive,
 * see V040's header comment. Exposed as-is rather than fabricating a
 * label.
 */
public record AwardSponsorTermResponse(
        Long awardSponsorTermId,
        Long sponsorTermId
) {
}
