/**
 * Record-level authorization for the archive (design revision 3.6a, private).
 *
 * <p>Identity and permissions are deliberately separate:
 * <ol>
 *   <li>{@link edu.bu.archive.application.authorization.ValidatedCognitoIdentity} -
 *       the issuer and subject of an already-validated Cognito access token;</li>
 *   <li>{@link edu.bu.archive.application.authorization.IdentityResolver} - maps that
 *       to a verified institutional identity and, when known, a Kuali employee
 *       PERSON_ID, through archive-owned identity links only;</li>
 *   <li>{@link edu.bu.archive.application.authorization.AccessScopeResolver} - turns
 *       the person's active archive grants into an {@link
 *       edu.bu.archive.application.authorization.AccessScope};</li>
 *   <li>{@link edu.bu.archive.application.authorization.RecordAccessEvaluator} -
 *       decides one record against that scope under an explicit
 *       {@link edu.bu.archive.application.authorization.AuthorizationPolicy}.</li>
 * </ol>
 *
 * <p>STATUS: record authorization is NOT ENFORCED anywhere yet. Nothing in this
 * package is called from live request handling. Real BU federation and the
 * BU attribute mapping are NOT VERIFIED (awaiting BU IAM); see
 * {@link edu.bu.archive.application.authorization.AwaitingIamConfirmationAttributeSource}.
 */
package edu.bu.archive.application.authorization;
