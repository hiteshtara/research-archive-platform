package edu.bu.archive.application.authorization.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import edu.bu.archive.application.authorization.IdentityLinkStatus;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.fixtures.InMemoryIdentityLinkRepository;

/** US-SEC-004: who the actor is, and which request a row belongs to. */
class RequestActivityContextTest {

    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_SYN";
    private static final ValidatedCognitoIdentity LINKED =
            new ValidatedCognitoIdentity(ISSUER, "syn-sub-linked");
    private static final ValidatedCognitoIdentity UNLINKED =
            new ValidatedCognitoIdentity(ISSUER, "syn-sub-unlinked");

    private final InMemoryIdentityLinkRepository links = new InMemoryIdentityLinkRepository();

    @BeforeEach
    void request() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        links.link(LINKED, "ARP-SYN-LINKED-0001", null, "syn-linked", IdentityLinkStatus.ACTIVE);
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private RequestActivityContext context(Optional<ValidatedCognitoIdentity> who) {
        return new RequestActivityContext(() -> who, new IdentityResolver(links), Optional::empty);
    }

    @Test
    @DisplayName("a linked caller is recorded by institutional identifier")
    void aLinkedCallerIsTheInstitutionalIdentifier() {
        assertThat(context(Optional.of(LINKED)).actor()).contains("ARP-SYN-LINKED-0001");
    }

    @Test
    @DisplayName("an authenticated but unlinked caller is recorded by Cognito subject, never null")
    void anUnlinkedCallerIsTheCognitoSubject() {
        assertThat(context(Optional.of(UNLINKED)).actor())
                .contains(RequestActivityContext.COGNITO_SUBJECT_PREFIX + "syn-sub-unlinked");
    }

    @Test
    @DisplayName("only an unauthenticated request has no actor")
    void onlyUnauthenticatedHasNoActor() {
        assertThat(context(Optional.empty()).actor()).isEmpty();
    }

    @Test
    @DisplayName("an identity-store failure falls back to the subject instead of throwing")
    void aResolutionFailureFallsBackToTheSubject() {
        var failing = new RequestActivityContext(() -> Optional.of(LINKED),
                new IdentityResolver(links) {
                    @Override
                    public edu.bu.archive.application.authorization.IdentityResolution resolve(
                            ValidatedCognitoIdentity identity) {
                        throw new IllegalStateException("store down");
                    }
                }, Optional::empty);
        assertThat(failing.actor()).contains("cognito-sub:syn-sub-linked");
    }

    @Test
    @DisplayName("the actor is resolved once per request")
    void theActorIsCachedOnTheRequest() {
        AtomicInteger lookups = new AtomicInteger();
        var counting = new RequestActivityContext(() -> {
            lookups.incrementAndGet();
            return Optional.of(LINKED);
        }, new IdentityResolver(links), Optional::empty);

        counting.actor();
        counting.actor();
        counting.actor();
        assertThat(lookups).hasValue(1);
    }

    @Test
    @DisplayName("one correlation id per request, a new one for the next request")
    void oneCorrelationIdPerRequest() {
        var context = context(Optional.of(LINKED));
        String first = context.correlationId();
        assertThat(first).isNotBlank().isEqualTo(context.correlationId());

        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThat(context.correlationId()).isNotBlank().isNotEqualTo(first);
    }

    @Test
    @DisplayName("the search timing cid is reused when the request already has one")
    void theSearchTimingCidIsReused() {
        var context = new RequestActivityContext(() -> Optional.of(LINKED),
                new IdentityResolver(links), () -> Optional.of("a1b2c3d4"));
        assertThat(context.correlationId()).isEqualTo("a1b2c3d4");
    }
}
