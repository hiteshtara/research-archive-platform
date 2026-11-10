package edu.bu.archive.application.search;

/**
 * Why a record matched, reported by the database rather than guessed.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>QA TC-040 reported that Award team-member matches do not explain the
 * role that matched. The cause was that the explanation was inferred: the
 * service tested the typed term against the handful of fields present in the
 * result DTO, and when none of them contained it - because the real match was
 * a non-PI team member, a sponsor acronym, a modification number or a grant
 * number - it fell through to the literal string "Award", rendered as
 * "Matched on: Award (100527-00001)". True, and useless.
 *
 * <p>Measured on DEV: all 25 Negotiation rows for "WILSON WONG" came back
 * explained as "Negotiation", because the match was the associated Award's
 * principal investigator.
 *
 * <p>So for the fields a result does not carry, the reason is asked of the
 * database for the handful of records that need it - one query per search,
 * not per row. A record whose reason cannot be established gets NO
 * explanation rather than a fabricated one.
 *
 * @param recordId the award_id this explains
 * @param field a human-readable field name, e.g. "Team member"
 * @param value what matched, e.g. the person's name and role
 */
public record MatchExplanation(long recordId, String field, String value) {
}
