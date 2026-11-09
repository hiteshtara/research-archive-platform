package edu.bu.archive.application.authorization.viewaccessas.demo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import edu.bu.archive.application.authorization.RecordContact;
import edu.bu.archive.application.authorization.RecordFacts;
import edu.bu.archive.application.authorization.RecordFactsRepository;
import edu.bu.archive.application.authorization.RecordModule;
import edu.bu.archive.application.authorization.UnitHierarchy;
import edu.bu.archive.application.authorization.fixtures.MapUnitHierarchy;
import edu.bu.archive.application.authorization.viewaccessas.SyntheticProfile;

/**
 * The synthetic world the View Access As demonstration evaluates against.
 *
 * <p>TEST SCOPE ONLY, like everything under {@code viewaccessas}. No BU data
 * appears here and no archive row is read: every record is a
 * {@link RecordFacts} value built in this file.
 *
 * <p><strong>Fixtures only — no rules.</strong> This class decides what the
 * records and grants ARE. It never decides who may see them; that is the
 * engine's job, and a fixture file that answered it would make the
 * demonstration agree with itself.
 *
 * <h2>Why the version keys are numbers</h2>
 *
 * {@code versionKey} is the {@code award_id} as text, because that is what the
 * production facts query renders ({@code award_id::text}) and what
 * {@code requireAward} compares against {@code String.valueOf(awardId)}. A
 * readable key like {@code "SYN-AWD-P-1"} would make every lookup miss, and
 * every assertion would then pass or fail for the wrong reason — a trap
 * already recorded in {@code Sec003AiPartialFamilyAccessTest}.
 *
 * <h2>What each family is for</h2>
 *
 * <ul>
 *   <li><b>SYN-AWD-A</b> — lead unit {@code SYN-U-100}. The plain department
 *       case: a UNIT grant on that unit reaches every version.
 *   <li><b>SYN-AWD-P</b> — unit {@code SYN-U-900}, outside every unit grant.
 *       The research-staff contact joined at version 202 and is still on the
 *       current version. This is the SEC-003 demonstration: under
 *       {@code PER_VERSION} the profile is denied on 201 and permitted on 202
 *       and 203, so the family is PARTIAL — and because the current version IS
 *       visible, the family-spanning read reaches the AI gate and is refused
 *       with {@code AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS}. Switching the policy
 *       to {@code FAMILY_WIDE} changes NOTHING here, which is the whole point
 *       of SEC-003: contact-derived access is decided against the version
 *       being read whatever {@code versionScope} says. {@code SYN-AWD-D} is
 *       the contrast — its IO grant does follow the policy, and its second
 *       version flips to PERMIT under {@code FAMILY_WIDE}.
 *   <li><b>SYN-AWD-S</b> — the opposite shape, and the one the business
 *       question turns on: the contact is on the FIRST version and not on the
 *       current one. {@code requireEveryAwardVersion} reads the current version
 *       first, so the family-spanning read fails as a 404 before the
 *       partial-access check is reached. The two families together show that
 *       "partial family" and "no access at all" are different outcomes of the
 *       same rule.
 *   <li><b>SYN-AWD-C</b> — the control for the above: the same contact on
 *       EVERY version, so the family is complete and the AI gate stays
 *       silent. Without this, a gate that refused everything would look
 *       correct.
 *   <li><b>SYN-AWD-K</b> — unit {@code SYN-U-110}, a child of
 *       {@code SYN-U-100}. Shows that a grant's {@code includeDescendants}
 *       and the policy's {@code departmentMatch} must BOTH agree.
 *   <li><b>SYN-AWD-D</b> — an IO value on the first version only, so IO is
 *       visibly version-specific too.
 *   <li><b>SYN-AWD-R</b> — a non-employee (rolodex) PI. Has no Kuali
 *       PERSON_ID, so it can never match a signed-in person however the
 *       policy is set.
 * </ul>
 */
public final class DemoModel {

    /** The Kuali principal the Research Staff profile is keyed on. Synthetic. */
    public static final String STAFF_PERSON_ID = "SYNP-3001";

    /** The IO value the IO profile holds. Synthetic. */
    public static final String IO_VALUE = "SYN-IO-0004";

    public static final String DEPARTMENT_UNIT = "SYN-U-100";
    public static final String CHILD_UNIT = "SYN-U-110";
    public static final String OTHER_UNIT = "SYN-U-900";

    private static final String ROLE_PI = "PI";

    /** Qualifying contact roles. Provisional — see the harness's known limits. */
    public static final Set<String> RESEARCH_STAFF_ROLES = Set.of("PI", "MPI", "COI");

    private DemoModel() {
    }

    // ── profiles ────────────────────────────────────────────────────────

    public static List<SyntheticProfile> profiles() {
        return List.of(
                SyntheticProfile.named("No Access",
                                "Enrolled, no grants at all. The baseline: every record denies.")
                        .build(),
                SyntheticProfile.named("CENTRAL",
                                "Central administration. Reaches every module and every version.")
                        .central().build(),
                SyntheticProfile.named("UNIT (exact)",
                                "A UNIT grant on " + DEPARTMENT_UNIT
                                        + " with includeDescendants = false.")
                        .unit(DEPARTMENT_UNIT, false).build(),
                SyntheticProfile.named("UNIT (+ descendants)",
                                "The same unit with includeDescendants = true. Reaches "
                                        + CHILD_UNIT + " ONLY when the policy also says "
                                        + "LEAD_UNIT_WITH_DESCENDANTS.")
                        .unit(DEPARTMENT_UNIT, true).build(),
                SyntheticProfile.named("IO",
                                "An IO grant for " + IO_VALUE
                                        + ", matched against the record's IO values.")
                        .io(IO_VALUE).build(),
                SyntheticProfile.named("Research Staff",
                                "A CONTACT_DERIVATION grant plus Kuali principal "
                                        + STAFF_PERSON_ID
                                        + ". Reaches only records this principal is a "
                                        + "qualifying contact on.")
                        .researchStaff(STAFF_PERSON_ID).build());
    }

    public static Optional<SyntheticProfile> profile(String name) {
        return profiles().stream().filter(p -> p.name().equals(name)).findFirst();
    }

    // ── records ─────────────────────────────────────────────────────────

    /** One Award family: a business number and its versions, oldest first. */
    public record Family(String familyKey, String label, String leadUnit, List<RecordFacts> versions) {
    }

    /** A Subaward and the Award versions that fund it. */
    public record Subaward(long subawardId, String label, List<Long> fundingAwardIds) {
    }

    /** A Negotiation and the basis it offers the engine. */
    public record Negotiation(
            long negotiationId,
            String label,
            String leadUnitNumber,
            String awardNumber,
            String proposalNumber) {
    }

    private static final List<RecordContact> NO_CONTACTS = List.of();
    private static final List<RecordContact> STAFF_IS_PI =
            List.of(RecordContact.employee(STAFF_PERSON_ID, ROLE_PI));
    private static final List<RecordContact> ROLODEX_PI =
            List.of(RecordContact.nonEmployee(ROLE_PI));

    public static List<Family> families() {
        return List.of(
                family("SYN-AWD-A", "Department Award, three versions", DEPARTMENT_UNIT,
                        version("101", "SYN-AWD-A", DEPARTMENT_UNIT, NO_CONTACTS, Set.of()),
                        version("102", "SYN-AWD-A", DEPARTMENT_UNIT, NO_CONTACTS, Set.of()),
                        version("103", "SYN-AWD-A", DEPARTMENT_UNIT, NO_CONTACTS, Set.of())),

                family("SYN-AWD-P", "SEC-003: contact joined at version 202", OTHER_UNIT,
                        version("201", "SYN-AWD-P", OTHER_UNIT, NO_CONTACTS, Set.of()),
                        version("202", "SYN-AWD-P", OTHER_UNIT, STAFF_IS_PI, Set.of()),
                        version("203", "SYN-AWD-P", OTHER_UNIT, STAFF_IS_PI, Set.of())),

                family("SYN-AWD-S", "SEC-003: contact left before the current version", OTHER_UNIT,
                        version("701", "SYN-AWD-S", OTHER_UNIT, STAFF_IS_PI, Set.of()),
                        version("702", "SYN-AWD-S", OTHER_UNIT, NO_CONTACTS, Set.of())),

                family("SYN-AWD-C", "Control: the same contact on every version", OTHER_UNIT,
                        version("301", "SYN-AWD-C", OTHER_UNIT, STAFF_IS_PI, Set.of()),
                        version("302", "SYN-AWD-C", OTHER_UNIT, STAFF_IS_PI, Set.of())),

                family("SYN-AWD-K", "Child unit of " + DEPARTMENT_UNIT, CHILD_UNIT,
                        version("401", "SYN-AWD-K", CHILD_UNIT, NO_CONTACTS, Set.of()),
                        version("402", "SYN-AWD-K", CHILD_UNIT, NO_CONTACTS, Set.of())),

                family("SYN-AWD-D", "IO value on the first version only", OTHER_UNIT,
                        version("501", "SYN-AWD-D", OTHER_UNIT, NO_CONTACTS, Set.of(IO_VALUE)),
                        version("502", "SYN-AWD-D", OTHER_UNIT, NO_CONTACTS, Set.of())),

                family("SYN-AWD-R", "Non-employee (rolodex) PI, no Kuali principal", OTHER_UNIT,
                        version("601", "SYN-AWD-R", OTHER_UNIT, ROLODEX_PI, Set.of())));
    }

    public static List<Subaward> subawards() {
        return List.of(
                new Subaward(9001L, "Funded by SYN-AWD-P version 202 (the permitted version)",
                        List.of(202L)),
                new Subaward(9002L, "Funded by SYN-AWD-P version 201 (a denied version)",
                        List.of(201L)),
                new Subaward(9003L, "Funded by SYN-AWD-A version 101 (department Award)",
                        List.of(101L)),
                new Subaward(9004L, "Funding reference that resolves to no archived Award",
                        List.of(999999L)));
    }

    public static List<Negotiation> negotiations() {
        return List.of(
                new Negotiation(7001L, "Associated with Award SYN-AWD-P",
                        null, "SYN-AWD-P", null),
                new Negotiation(7002L, "Lead unit " + DEPARTMENT_UNIT + ", no association",
                        DEPARTMENT_UNIT, null, null),
                new Negotiation(7003L, "No lead unit and no association at all",
                        null, null, null));
    }

    public static UnitHierarchy unitHierarchy() {
        return new MapUnitHierarchy(Map.of(
                CHILD_UNIT, DEPARTMENT_UNIT,
                DEPARTMENT_UNIT, "SYN-U-001",
                OTHER_UNIT, "SYN-U-001"));
    }

    private static Family family(String key, String label, String unit, RecordFacts... versions) {
        return new Family(key, label, unit, List.of(versions));
    }

    private static RecordFacts version(
            String versionKey, String familyKey, String unit,
            List<RecordContact> contacts, Set<String> ios) {
        return new RecordFacts(RecordModule.AWARD, versionKey, familyKey, unit, contacts, ios);
    }

    // ── the facts the production service reads ──────────────────────────

    /**
     * The model, behind the real {@link RecordFactsRepository} port, so
     * {@code RecordAuthorizationService} can run against it unmodified.
     *
     * <p>Read-only by construction: there is no write method on the port and
     * none here.
     */
    public static final class DemoFacts implements RecordFactsRepository {

        private final Map<String, Family> byFamily = new LinkedHashMap<>();
        private final Map<Long, String> familyOfVersionId = new LinkedHashMap<>();
        private final Map<Long, Subaward> subawardsById = new LinkedHashMap<>();
        private final Map<Long, Negotiation> negotiationsById = new LinkedHashMap<>();

        public DemoFacts() {
            for (Family f : families()) {
                byFamily.put(f.familyKey(), f);
                for (RecordFacts version : f.versions()) {
                    familyOfVersionId.put(Long.parseLong(version.versionKey()), f.familyKey());
                }
            }
            subawards().forEach(s -> subawardsById.put(s.subawardId(), s));
            negotiations().forEach(n -> negotiationsById.put(n.negotiationId(), n));
        }

        @Override
        public Optional<String> awardNumberForId(long awardId) {
            return Optional.ofNullable(familyOfVersionId.get(awardId));
        }

        @Override
        public List<RecordFacts> awardFamily(String awardNumber) {
            Family f = byFamily.get(awardNumber);
            return f == null ? List.of() : f.versions();
        }

        @Override
        public Optional<String> currentAwardVersionKey(String awardNumber) {
            List<RecordFacts> family = awardFamily(awardNumber);
            return family.isEmpty()
                    ? Optional.empty()
                    : Optional.of(family.get(family.size() - 1).versionKey());
        }

        @Override
        public Optional<String> proposalNumberForId(long proposalId) {
            return Optional.empty();
        }

        @Override
        public List<RecordFacts> proposalFamily(String proposalNumber) {
            return List.of();
        }

        @Override
        public Optional<String> latestProposalVersionKey(String proposalNumber) {
            return Optional.empty();
        }

        @Override
        public List<Long> subawardFamilyFundingAwardIds(long subawardId) {
            Subaward s = subawardsById.get(subawardId);
            if (s == null) {
                return List.of();
            }
            /*
             * The port's contract: a funding reference that resolves to no
             * archived Award is filtered HERE, because
             * archive.subaward_funding.award_id has no foreign key and a
             * dangling reference must never authorise anything. Fixture 9004
             * exists to exercise exactly that.
             */
            List<Long> resolvable = new ArrayList<>();
            for (Long awardId : s.fundingAwardIds()) {
                if (familyOfVersionId.containsKey(awardId)) {
                    resolvable.add(awardId);
                }
            }
            return List.copyOf(resolvable);
        }

        @Override
        public Optional<NegotiationFacts> negotiationFacts(long negotiationId) {
            Negotiation n = negotiationsById.get(negotiationId);
            return n == null
                    ? Optional.empty()
                    : Optional.of(new NegotiationFacts(
                            n.leadUnitNumber(), n.awardNumber(), n.proposalNumber()));
        }
    }
}
