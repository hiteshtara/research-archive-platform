package edu.bu.archive.application.authorization;

import java.util.Set;

/**
 * The only IO resolver for real data until the authoritative IO field is
 * confirmed (decision D-A). Returns no IO values, so IO grants reach no real
 * record. Do NOT replace with award_version.account_number on the strength
 * of the requirements' examples alone.
 */
public final class DisabledIoResolver implements IoResolver {

    @Override
    public Set<String> ioValuesFor(RecordModule module, String versionKey) {
        return Set.of();
    }
}
