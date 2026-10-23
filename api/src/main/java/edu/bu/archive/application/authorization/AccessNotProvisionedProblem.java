package edu.bu.archive.application.authorization;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The response body for an authenticated user whose archive access is not
 * provisioned. Says what happened and what to do, and nothing about which
 * records exist. Not yet wired into live request handling.
 */
public final class AccessNotProvisionedProblem {

    public static final int STATUS = 403;
    public static final String CODE = "ACCESS_NOT_PROVISIONED";
    public static final String MESSAGE =
            "You are signed in, but your access to the Research Archive has not been set up yet. "
                    + "Contact the archive administrators to request access.";

    private AccessNotProvisionedProblem() {
    }

    public static Map<String, Object> body(String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", STATUS);
        body.put("code", CODE);
        body.put("message", MESSAGE);
        body.put("path", path);
        return body;
    }
}
