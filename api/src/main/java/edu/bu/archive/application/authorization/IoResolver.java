package edu.bu.archive.application.authorization;

import java.util.Set;

/** Which IO values a record version carries. */
public interface IoResolver {

    Set<String> ioValuesFor(RecordModule module, String versionKey);
}
