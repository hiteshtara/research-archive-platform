package edu.bu.archive.application.authorization;

import java.util.Optional;

/**
 * Port: reads one user-pool profile by its exact Cognito username, server
 * side, with the API's own IAM credentials (never with anything the browser
 * sends). Empty when no such profile exists. Any other failure throws, and
 * enrollment fails closed.
 */
public interface CognitoProfileReader {

    Optional<CognitoProfile> read(String username);
}
