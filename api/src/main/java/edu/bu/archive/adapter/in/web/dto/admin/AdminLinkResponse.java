package edu.bu.archive.adapter.in.web.dto.admin;

import java.time.Instant;

import edu.bu.archive.application.authorization.IdentityLink;

/** One identity link. The Cognito subject is shown so an administrator can tell two links apart. */
public record AdminLinkResponse(
        long linkId,
        String cognitoIssuer,
        String cognitoSubject,
        String kualiPersonId,
        String loginName,
        String status,
        Instant verifiedAt) {

    public static AdminLinkResponse from(IdentityLink link) {
        return new AdminLinkResponse(
                link.id(),
                link.cognitoIdentity().issuer(),
                link.cognitoIdentity().subject(),
                link.kualiPersonId().map(id -> id.value()).orElse(null),
                link.loginName(),
                link.status().name(),
                link.verifiedAt());
    }
}
