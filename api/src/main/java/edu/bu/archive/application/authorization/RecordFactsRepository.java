package edu.bu.archive.application.authorization;

import java.util.List;
import java.util.Optional;

/** Loads the authorization-relevant facts of archived records. Read-only. */
public interface RecordFactsRepository {

    Optional<String> awardNumberForId(long awardId);

    /** Every version of an Award family, with contacts; IO values filled by the IoResolver. */
    List<RecordFacts> awardFamily(String awardNumber);

    Optional<String> proposalNumberForId(long proposalId);

    /** Every version of a Proposal family, with contacts. */
    List<RecordFacts> proposalFamily(String proposalNumber);

    /** The key of the version an endpoint reads by number: the current Award / latest Proposal. */
    Optional<String> currentAwardVersionKey(String awardNumber);

    Optional<String> latestProposalVersionKey(String proposalNumber);

    /**
     * The award_id of every funding Award of a Subaward, excluding rows whose
     * award_id resolves to no archived Award.
     *
     * <p>Resolvability is filtered HERE, not by the caller:
     * archive.subaward_funding.award_id has no foreign key, so a dangling
     * reference is possible, and a reference that points at nothing must
     * never be able to authorise anything.
     */
    List<Long> subawardFamilyFundingAwardIds(long subawardId);

    /**
     * The authorization basis for one Negotiation: its recorded lead unit,
     * and its association ONLY where the association type explicitly names
     * one. Never resolves associated_document_id by string match alone -
     * 6,073 of 10,775 ids in dev match more than one record kind.
     *
     * @return empty when the Negotiation does not exist
     */
    java.util.Optional<NegotiationFacts> negotiationFacts(long negotiationId);

    /**
     * What a Negotiation offers the authorization engine. Any field may be
     * absent: 57 Negotiations have no lead unit, and 8,533 have no real
     * association at all.
     *
     * @param leadUnitNumber from negotiation_search_attribute (1:1, FK-enforced)
     * @param awardNumber    set ONLY when association type is 'Award'
     * @param proposalNumber set ONLY when association type is 'Institutional Proposal'
     */
    record NegotiationFacts(
            String leadUnitNumber,
            String awardNumber,
            String proposalNumber
    ) { }
}
