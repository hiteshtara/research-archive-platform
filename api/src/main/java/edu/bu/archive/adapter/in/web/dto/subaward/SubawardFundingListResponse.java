package edu.bu.archive.adapter.in.web.dto.subaward;

import java.util.List;

/**
 * The Subaward's funding relationships, plus whether the caller is seeing
 * all of them.
 *
 * <p>The envelope exists because of the ANY-match authorization rule.
 * Reaching a Subaward requires only ONE accessible funding Award, and
 * unauthorized funding rows are omitted entirely (decision D5). Handing back
 * a bare list would leave the UI counting what it received and presenting
 * that as the record's real number of funding Awards - so a Subaward funded
 * by three Awards, one of them visible, would be labelled "Associated Award"
 * in the singular. That is not a partial answer, it is a false one.
 *
 * <p>{@code restricted} is true ONLY when rows were actually removed, never
 * merely because the caller is scoped. A scoped caller who can see every
 * funding Award of this Subaward therefore still gets the exact count, and
 * learns nothing. With enforcement off it is always false and the UI renders
 * exactly what it rendered before.
 *
 * <p>It deliberately does NOT say how many rows were removed, or anything
 * about them. "Some funding Awards are not shown to you" is the smallest
 * true statement that stops the count being a lie.
 */
public record SubawardFundingListResponse(
        List<SubawardFundingResponse> funding,
        boolean restricted
) {
}
