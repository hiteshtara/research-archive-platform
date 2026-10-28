package edu.bu.archive.application.authorization.admin.bootstrap;

import java.util.Locale;
import java.util.Objects;

/**
 * Where the operator believes they are pointing.
 *
 * <p>Supplied by the environment the utility runs in, and compared against
 * what the operator typed. The point is not that either side is trustworthy
 * on its own - it is that bootstrapping the wrong environment requires two
 * independent mistakes rather than one.
 */
public record BootstrapTarget(
        String awsAccountId,
        String environment,
        String cognitoUserPoolId,
        String cognitoRegion) {

    public BootstrapTarget {
        awsAccountId = required(awsAccountId, "awsAccountId");
        environment = required(environment, "environment").toLowerCase(Locale.ROOT);
        cognitoUserPoolId = required(cognitoUserPoolId, "cognitoUserPoolId");
        cognitoRegion = required(cognitoRegion, "cognitoRegion");
    }

    /**
     * The only issuer a token from this pool can carry. Derived rather than
     * configured separately, so the issuer and the pool cannot disagree.
     */
    public String expectedIssuer() {
        return "https://cognito-idp." + cognitoRegion + ".amazonaws.com/" + cognitoUserPoolId;
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
