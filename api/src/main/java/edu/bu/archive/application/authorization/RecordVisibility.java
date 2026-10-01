package edu.bu.archive.application.authorization;

import java.util.function.Supplier;

/**
 * What services need to filter related records and lists. {@link #ALL}
 * keeps today's behaviour; {@link RecordAuthorizationService} implements it
 * when record authorization is enforced.
 */
public interface RecordVisibility {

    /** Not enforced, or a Central caller. */
    boolean unrestricted();

    boolean canSeeAward(long awardId);

    boolean canSeeAwardNumber(String awardNumber);

    boolean canSeeProposalNumber(String proposalNumber);

    <T> Supplier<T> propagate(Supplier<T> work);

    RecordVisibility ALL = new RecordVisibility() {
        @Override public boolean unrestricted() { return true; }
        @Override public boolean canSeeAward(long awardId) { return true; }
        @Override public boolean canSeeAwardNumber(String awardNumber) { return true; }
        @Override public boolean canSeeProposalNumber(String proposalNumber) { return true; }
        @Override public <T> Supplier<T> propagate(Supplier<T> work) { return work; }
    };
}
