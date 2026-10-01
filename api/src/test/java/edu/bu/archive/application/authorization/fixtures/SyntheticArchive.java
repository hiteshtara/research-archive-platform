package edu.bu.archive.application.authorization.fixtures;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.bu.archive.application.authorization.AccessOutcome;
import edu.bu.archive.application.authorization.AccessScopeResolver;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.IdentityLinkStatus;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.RecordContact;
import edu.bu.archive.application.authorization.RecordFacts;
import edu.bu.archive.application.authorization.RecordModule;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/**
 * TEST-ONLY synthetic archive: invented people, units, IO values and records.
 * No real BU names, identifiers, units or award numbers - every value is
 * prefixed "SYN" and the issuer is a ".invalid" host.
 *
 * <p>Personas: CENTRAL, DEPARTMENT (unit SYN-U-100), RESEARCH_STAFF
 * (employee SYNP-1003, with a CONTACT_DERIVATION grant), AUTHORIZED_VIEWER
 * (IO SYN-IO-0004), MULTI (unit SYN-U-200 + IO SYN-IO-0005 + contact),
 * NO_GRANTS, UNMAPPED, AMBIGUOUS, REVOKED, SUSPENDED, and a reassigned login
 * name "syn-shared" that belonged to SYN-INST-1010 (now revoked, had CENTRAL)
 * and now belongs to a different person, SYN-INST-1011 (no grants).
 */
public final class SyntheticArchive {

    public static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    public final SyntheticCognitoFederation federation = new SyntheticCognitoFederation();
    public final InMemoryIdentityLinkRepository links = new InMemoryIdentityLinkRepository();
    public final InMemoryAccessGrantRepository grants = new InMemoryAccessGrantRepository();
    public final MapUnitHierarchy units = new MapUnitHierarchy(Map.of(
            "SYN-U-110", "SYN-U-100",   // child of the department unit
            "SYN-U-100", "SYN-U-001",
            "SYN-U-200", "SYN-U-001",
            "SYN-U-300", "SYN-U-001"
    ));
    public final IdentityResolver identityResolver = new IdentityResolver(links);
    public final AccessScopeResolver scopeResolver = new AccessScopeResolver(grants, CLOCK);

    public final ValidatedCognitoIdentity central;
    public final ValidatedCognitoIdentity department;
    public final ValidatedCognitoIdentity researchStaff;
    public final ValidatedCognitoIdentity authorizedViewer;
    public final ValidatedCognitoIdentity multi;
    public final ValidatedCognitoIdentity noGrants;
    public final ValidatedCognitoIdentity unmapped;
    public final ValidatedCognitoIdentity ambiguousA;
    public final ValidatedCognitoIdentity revoked;
    public final ValidatedCognitoIdentity suspended;
    public final ValidatedCognitoIdentity reassignedOldHolder;
    public final ValidatedCognitoIdentity reassignedNewHolder;

    public SyntheticArchive() {
        central = enrol("syn-nameid-1001", "SYN-INST-1001", "SYNP-1001", "syn-central");
        grants.grant("SYN-INST-1001", GrantType.CENTRAL, null, false, null);

        department = enrol("syn-nameid-1002", "SYN-INST-1002", "SYNP-1002", "syn-dept");
        grants.grant("SYN-INST-1002", GrantType.UNIT, "SYN-U-100", true, null);

        researchStaff = enrol("syn-nameid-1003", "SYN-INST-1003", "SYNP-1003", "syn-pi");
        grants.grant("SYN-INST-1003", GrantType.CONTACT_DERIVATION, null, false, null);

        authorizedViewer = enrol("syn-nameid-1004", "SYN-INST-1004", "SYNP-1004", "syn-oav");
        grants.grant("SYN-INST-1004", GrantType.IO, null, false, "SYN-IO-0004");

        multi = enrol("syn-nameid-1005", "SYN-INST-1005", "SYNP-1005", "syn-multi");
        grants.grant("SYN-INST-1005", GrantType.UNIT, "SYN-U-200", false, null);
        grants.grant("SYN-INST-1005", GrantType.IO, null, false, "SYN-IO-0005");
        grants.grant("SYN-INST-1005", GrantType.CONTACT_DERIVATION, null, false, null);

        noGrants = enrol("syn-nameid-1006", "SYN-INST-1006", "SYNP-1006", "syn-nogrants");

        unmapped = signIn("syn-nameid-1007", "SYN-INST-1007", "syn-unmapped");   // no link written

        // One institutional identity linked to two different Kuali people.
        ambiguousA = enrol("syn-nameid-1008a", "SYN-INST-1008", "SYNP-1008A", "syn-amb");
        enrol("syn-nameid-1008b", "SYN-INST-1008", "SYNP-1008B", "syn-amb2");
        grants.grant("SYN-INST-1008", GrantType.CENTRAL, null, false, null);

        revoked = signIn("syn-nameid-1009", "SYN-INST-1009", "syn-revoked");
        links.link(revoked, "SYN-INST-1009", "SYNP-1009", "syn-revoked", IdentityLinkStatus.REVOKED);
        grants.grant("SYN-INST-1009", GrantType.CENTRAL, null, false, null);

        suspended = enrol("syn-nameid-1012", "SYN-INST-1012", "SYNP-1012", "syn-suspended");
        grants.grant("SYN-INST-1012", GrantType.CENTRAL, null, false, null);
        links.suspend("SYN-INST-1012");

        // Login name "syn-shared" reassigned between two different people.
        reassignedOldHolder = signIn("syn-nameid-1010", "SYN-INST-1010", "syn-shared");
        links.link(reassignedOldHolder, "SYN-INST-1010", "SYNP-1010", "syn-shared", IdentityLinkStatus.REVOKED);
        grants.grant("SYN-INST-1010", GrantType.CENTRAL, null, false, null);
        reassignedNewHolder = enrol("syn-nameid-1011", "SYN-INST-1011", "SYNP-1011", "syn-shared");
    }

    private ValidatedCognitoIdentity signIn(String nameId, String institutionalId, String loginName) {
        var assertion = new SyntheticSamlAssertion(
                "https://idp.test.invalid/synthetic",
                SyntheticSamlAssertion.PERSISTENT,
                nameId,
                Map.of(SyntheticSamlAssertion.INSTITUTIONAL_ID_ATTRIBUTE, institutionalId,
                        SyntheticSamlAssertion.LOGIN_NAME_ATTRIBUTE, loginName));
        return federation.signIn(assertion).validatedIdentity();
    }

    /** Simulated trusted enrollment: sign in, then write an ACTIVE link. */
    private ValidatedCognitoIdentity enrol(String nameId, String institutionalId, String personId, String loginName) {
        var identity = signIn(nameId, institutionalId, loginName);
        links.link(identity, institutionalId, personId, loginName, IdentityLinkStatus.ACTIVE);
        return identity;
    }

    public AccessOutcome outcomeFor(ValidatedCognitoIdentity identity) {
        return scopeResolver.resolve(identityResolver.resolve(identity));
    }

    // --- synthetic records ------------------------------------------------

    public static RecordFacts award(String version, String family, String unit, List<RecordContact> contacts, Set<String> ios) {
        return new RecordFacts(RecordModule.AWARD, version, family, unit, contacts, ios);
    }

    /** Award family A in the department unit. */
    public static final RecordFacts AWARD_A_V1 = award("SYN-AWD-A-1", "SYN-AWD-A", "SYN-U-100", List.of(), Set.of());
    public static final RecordFacts AWARD_A_V2 = award("SYN-AWD-A-2", "SYN-AWD-A", "SYN-U-100", List.of(), Set.of());
    /** In a child unit of the department unit. */
    public static final RecordFacts AWARD_CHILD_UNIT = award("SYN-AWD-K-1", "SYN-AWD-K", "SYN-U-110", List.of(), Set.of());
    /** Family B: research-staff person is PI on v2 only; v1 moved unit. */
    public static final RecordFacts AWARD_B_V1 = award("SYN-AWD-B-1", "SYN-AWD-B", "SYN-U-100",
            List.of(RecordContact.nonEmployee("COI")), Set.of());
    public static final RecordFacts AWARD_B_V2 = award("SYN-AWD-B-2", "SYN-AWD-B", "SYN-U-300",
            List.of(RecordContact.employee("SYNP-1003", "PI"), RecordContact.nonEmployee("COI")), Set.of());
    /** Research-staff person is only a Key Person here. */
    public static final RecordFacts AWARD_KP_ONLY = award("SYN-AWD-C-1", "SYN-AWD-C", "SYN-U-300",
            List.of(RecordContact.employee("SYNP-1003", "KP")), Set.of());
    /** Carries IO SYN-IO-0004 on v1 only (from a synthetic IO resolver). */
    public static final RecordFacts AWARD_IO_V1 = award("SYN-AWD-D-1", "SYN-AWD-D", "SYN-U-300", List.of(), Set.of("SYN-IO-0004"));
    public static final RecordFacts AWARD_IO_V2 = award("SYN-AWD-D-2", "SYN-AWD-D", "SYN-U-300", List.of(), Set.of());
    /** Unrelated award: nobody but Central may see it. */
    public static final RecordFacts AWARD_UNRELATED = award("SYN-AWD-Z-1", "SYN-AWD-Z", "SYN-U-999",
            List.of(RecordContact.employee("SYNP-9999", "PI")), Set.of("SYN-IO-9999"));
    /** Multi persona: unit SYN-U-200 award, and an IO award elsewhere. */
    public static final RecordFacts AWARD_U200 = award("SYN-AWD-E-1", "SYN-AWD-E", "SYN-U-200", List.of(), Set.of());
    public static final RecordFacts AWARD_IO_5 = award("SYN-AWD-F-1", "SYN-AWD-F", "SYN-U-300", List.of(), Set.of("SYN-IO-0005"));

    public static final RecordFacts PROPOSAL_MPI = new RecordFacts(RecordModule.PROPOSAL, "SYN-PRP-A-1", "SYN-PRP-A",
            "SYN-U-300", List.of(RecordContact.employee("SYNP-1003", "MPI")), Set.of());
    public static final RecordFacts PROPOSAL_DEPT = new RecordFacts(RecordModule.PROPOSAL, "SYN-PRP-B-1", "SYN-PRP-B",
            "SYN-U-100", List.of(), Set.of());
    /** Related to AWARD_A (same department) but in another unit: never reachable via the relationship. */
    public static final RecordFacts PROPOSAL_RELATED_OTHER_UNIT = new RecordFacts(RecordModule.PROPOSAL, "SYN-PRP-R-1",
            "SYN-PRP-R", "SYN-U-999", List.of(), Set.of());
    public static final RecordFacts NEGOTIATION_DEPT = new RecordFacts(RecordModule.NEGOTIATION, "SYN-NEG-1", "SYN-NEG-1",
            "SYN-U-100", List.of(RecordContact.employee("SYNP-1003", "PI")), Set.of());
    public static final RecordFacts NEGOTIATION_NO_UNIT = new RecordFacts(RecordModule.NEGOTIATION, "SYN-NEG-2", "SYN-NEG-2",
            null, List.of(), Set.of());
    public static final RecordFacts SUBAWARD = new RecordFacts(RecordModule.SUBAWARD, "SYN-SUB-1", "SYN-SUB-1",
            "SYN-U-100", List.of(RecordContact.employee("SYNP-1003", "PI")), Set.of());
    public static final RecordFacts IRB = new RecordFacts(RecordModule.IRB, "SYN-IRB-1", "SYN-IRB-1",
            "SYN-U-100", List.of(RecordContact.employee("SYNP-1003", "PI")), Set.of());
}
