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
}
