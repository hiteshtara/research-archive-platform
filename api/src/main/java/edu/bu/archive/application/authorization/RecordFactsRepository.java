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
}
