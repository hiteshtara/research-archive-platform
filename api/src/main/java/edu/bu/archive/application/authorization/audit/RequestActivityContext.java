package edu.bu.archive.application.authorization.audit;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.IdentityResolution;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/**
 * WHO and WHICH REQUEST, for an activity row (SEC-008 / US-SEC-004).
 *
 * <h2>Actor</h2>
 *
 * <p>The institutional identifier of the caller, resolved exactly as the
 * administrative API resolves its actor (the validated Cognito identity,
 * through {@link IdentityResolver}, accepted only as
 * {@link IdentityResolution.Mapped}). It is the key grants are held on, so a
 * reassigned login name cannot be mistaken for the same person.
 *
 * <p>An authenticated caller who does NOT resolve - not yet linked, revoked,
 * suspended, ambiguous - still made the request, and a row with a null actor
 * would make their activity unattributable. Those rows carry
 * {@code cognito-sub:<subject>}: the opaque Cognito subject, never the token,
 * never a login name or an email. V089 has no separate subject column, so the
 * prefix is what tells the two kinds of actor apart.
 *
 * <p>The actor is empty ONLY when there is no validated identity at all - an
 * unauthenticated request (the local permit-all profile, or a non-request
 * thread).
 *
 * <p>Resolution is cached on the request, so a request that writes several
 * rows resolves its actor once; and it never throws - an identity-store
 * failure falls back to the subject rather than turning an audit write into
 * a failed read.
 *
 * <h2>Correlation id</h2>
 *
 * <p>One id per HTTP request, cached on the request, so every row a request
 * writes shares it. Where the Global Search timing instrumentation has
 * already assigned the request an id (the {@code SEMANTIC_RELEVANCE cid=}
 * log lines and the {@code X-Search-Timing-Cid} header), that id is reused so
 * the audit row and the log lines read as one request; otherwise a random
 * UUID is minted. Neither is derived from anything the caller sent.
 */
public class RequestActivityContext {

    private static final Logger log = LoggerFactory.getLogger(RequestActivityContext.class);

    static final String ACTOR_ATTRIBUTE = RequestActivityContext.class.getName() + ".actor";
    static final String CORRELATION_ATTRIBUTE =
            RequestActivityContext.class.getName() + ".correlationId";

    /** Prefix for an authenticated caller with no resolvable institutional identifier. */
    public static final String COGNITO_SUBJECT_PREFIX = "cognito-sub:";

    private final CurrentIdentityProvider identities;
    private final IdentityResolver resolver;
    private final Supplier<Optional<String>> existingCorrelationId;

    /**
     * @param existingCorrelationId an id some earlier layer has already given
     *                              this request (the search timing cid), or
     *                              empty
     */
    public RequestActivityContext(CurrentIdentityProvider identities,
                                  IdentityResolver resolver,
                                  Supplier<Optional<String>> existingCorrelationId) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.existingCorrelationId =
                Objects.requireNonNull(existingCorrelationId, "existingCorrelationId");
    }

    /** The current request's actor; empty only when unauthenticated. */
    public Optional<String> actor() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            Object cached = request.getAttribute(ACTOR_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof CachedActor hit) {
                return Optional.ofNullable(hit.value());
            }
        }
        String resolved = resolveActor();
        if (request != null) {
            request.setAttribute(ACTOR_ATTRIBUTE, new CachedActor(resolved),
                    RequestAttributes.SCOPE_REQUEST);
        }
        return Optional.ofNullable(resolved);
    }

    private String resolveActor() {
        Optional<ValidatedCognitoIdentity> identity;
        try {
            identity = identities.current();
        } catch (RuntimeException failure) {
            log.warn("activity audit: could not read the current identity; actor recorded as absent");
            return null;
        }
        if (identity.isEmpty()) {
            return null;
        }
        try {
            if (resolver.resolve(identity.get()) instanceof IdentityResolution.Mapped mapped) {
                return mapped.institutionalIdentifier().value();
            }
        } catch (RuntimeException failure) {
            log.warn("activity audit: identity resolution failed; recording the Cognito subject");
        }
        return COGNITO_SUBJECT_PREFIX + identity.get().subject();
    }

    /** The current request's correlation id; never null. */
    public String correlationId() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            Object cached = request.getAttribute(CORRELATION_ATTRIBUTE,
                    RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof String id) {
                return id;
            }
        }
        String id;
        try {
            id = existingCorrelationId.get().filter(s -> !s.isBlank())
                    .orElseGet(() -> UUID.randomUUID().toString());
        } catch (RuntimeException failure) {
            id = UUID.randomUUID().toString();
        }
        if (request != null) {
            request.setAttribute(CORRELATION_ATTRIBUTE, id, RequestAttributes.SCOPE_REQUEST);
        }
        return id;
    }

    /** Distinguishes "resolved to nobody" from "not resolved yet" in the cache. */
    private record CachedActor(String value) { }
}
