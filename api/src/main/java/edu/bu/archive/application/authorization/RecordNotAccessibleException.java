package edu.bu.archive.application.authorization;

import java.util.NoSuchElementException;

/**
 * A record outside the caller's scope. Deliberately a NoSuchElementException
 * so it becomes the same 404 as a record that does not exist (design D-J,
 * proposal P7): a direct URL never confirms that an out-of-scope record exists.
 */
public class RecordNotAccessibleException extends NoSuchElementException {
    public RecordNotAccessibleException() {
        super("Record not found");
    }
}
