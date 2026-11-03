package edu.bu.archive.adapter.in.cli;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import edu.bu.archive.application.authorization.IdentityLinkRepository;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.admin.provisioning.CognitoDirectory;
import edu.bu.archive.application.authorization.admin.provisioning.EcsTaskMetadataRuntimeIdentity;
import edu.bu.archive.application.authorization.admin.provisioning.EnrollmentOutcome;
import edu.bu.archive.application.authorization.admin.provisioning.EnrollmentRequest;
import edu.bu.archive.application.authorization.admin.provisioning.IdentityEnrollmentCommand;

/**
 * Operator entry point for enrolling an ordinary BU research user.
 *
 * <p><strong>Not an HTTP endpoint, and it cannot become one by accident.</strong>
 * An {@link ApplicationRunner} behind the {@code grant-admin} profile, which no
 * deployment sets. Running it is a deliberate act: a one-off task with that
 * profile and {@code spring.main.web-application-type=none}, so no port is
 * opened while it holds the ability to create an archive identity.
 *
 * <p>It shares the {@code grant-admin} profile with the grant utility on
 * purpose - one operator surface, one isolated task family. The two never both
 * act: this runner does nothing unless an enrollment verb is present, the
 * grant runner stands down when one is, and an invocation carrying both verbs
 * is refused by whichever runs first, so neither order can half-apply.
 *
 * <pre>
 * --aws-account-id=770203350335
 * --environment=dev
 * --actor=&lt;the acting administrator's ARP&gt;
 * --cognito-issuer=https://cognito-idp.us-east-1.amazonaws.com/&lt;pool-id&gt;
 * --cognito-subject=&lt;the immutable sub UUID&gt;
 * --cognito-user-pool-id=&lt;pool-id&gt;
 * [--login-name=...]                          display only; never selects anyone
 * --operator=&lt;the person running this&gt;
 * --reason="..."
 * --approval-reference="..."
 * [--confirm]                                 without it, this is a dry run
 * </pre>
 *
 * <p>The archive identifier is MINTED on confirmation, never supplied. No
 * administrator membership and no research-data grant are created, so the
 * enrolled identity sees nothing until a grant is issued separately.
 *
 * <p>Exit status is 0 for a dry run that passed and for a completed
 * enrollment, and 1 for a refusal, so a wrapper script can branch on it.
 */
@Component
@Profile("grant-admin")
public class IdentityEnrollmentCli implements ApplicationRunner {

    private final ArchiveAdminService admin;
    private final AdminRoleRepository adminRoles;
    private final AdminIdentityRepository identities;
    private final IdentityLinkRepository links;

    public IdentityEnrollmentCli(ArchiveAdminService admin, AdminRoleRepository adminRoles,
                                 AdminIdentityRepository identities, IdentityLinkRepository links) {
        this.admin = admin;
        this.adminRoles = adminRoles;
        this.identities = identities;
        this.links = links;
    }

    @Override
    public void run(ApplicationArguments args) {
        /*
         * This runner and the grant runner share a profile. Each acts only
         * when its own verb is present, so a grant invocation does not enrol
         * and an enrollment invocation does not grant.
         */
        if (!args.containsOption("enrol-cognito-subject")
                && !args.containsOption("enroll-cognito-subject")) {
            return;
        }

        Map<String, String> options = new HashMap<>();
        for (String name : args.getOptionNames()) {
            options.put(name, args.getOptionValues(name).stream().findFirst().orElse(null));
        }
        /*
         * Both verbs in one invocation is ambiguous, not a convenience: the
         * grant runner stands down for enrollment, so proceeding would enrol
         * and silently issue no grant. Refuse before anything is written.
         */
        if (args.containsOption("grantee") || args.containsOption("revoke-grant-id")) {
            throw new IllegalArgumentException(
                    "this invocation carries both an enrollment verb and a grant verb; "
                            + "enrol first, then issue the grant as a separate task, so each "
                            + "action has its own approval and its own audit record");
        }

        String subject = options.getOrDefault("enrol-cognito-subject",
                options.get("enroll-cognito-subject"));

        EnrollmentRequest request = new EnrollmentRequest(
                options.get("aws-account-id"),
                options.get("environment"),
                options.get("actor"),
                options.get("cognito-issuer"),
                subject,
                options.get("cognito-user-pool-id"),
                options.get("login-name"),
                options.get("operator"),
                options.get("reason"),
                options.get("approval-reference"),
                args.containsOption("dry-run"),
                args.containsOption("confirm"));

        EnrollmentOutcome outcome = new IdentityEnrollmentCommand(
                admin, adminRoles, identities, links,
                // Read from the ECS task metadata endpoint, NOT from the
                // flags - the flags are what we are checking.
                new EcsTaskMetadataRuntimeIdentity())
                .withDirectory(directory())
                .run(request);

        print(outcome);
        if (outcome.result() == EnrollmentOutcome.Result.REFUSED) {
            throw new IllegalStateException(outcome.message());
        }
    }

    /*
     * No Cognito directory ships yet - the AWS SDK's Cognito client is not a
     * dependency of this module, and the task role has no cognito-idp
     * permission. Until both exist, a confirmed enrollment is REFUSED and a
     * dry run warns, which is the correct direction: the subject check is the
     * reason this utility can be trusted at all.
     */
    private static CognitoDirectory directory() {
        return CognitoDirectory.UNAVAILABLE;
    }

    private static void print(EnrollmentOutcome outcome) {
        System.out.println();
        System.out.println("Research Archive identity enrollment - " + outcome.result());
        outcome.checks().forEach(line -> System.out.println("  " + line));
        outcome.problems().forEach(line -> System.out.println("  FAIL   " + line));
        System.out.println();
        System.out.println(outcome.message());
        if (outcome.wrote()) {
            System.out.println();
            System.out.println("Audit evidence - keep this:");
            System.out.println("  SELECT identity_link_id, institutional_identifier, method,"
                    + " status, verified_by, verified_at FROM authz.identity_link;");
            System.out.println("  SELECT actor, action, occurred_at, detail ->> 'note'"
                    + " FROM authz.access_audit ORDER BY audit_id;");
        }
        System.out.println();
    }
}
