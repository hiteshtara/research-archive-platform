package edu.bu.archive.adapter.in.cli;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.admin.provisioning.EcsTaskMetadataRuntimeIdentity;
import edu.bu.archive.application.authorization.admin.provisioning.IdentityLinkCommand;
import edu.bu.archive.application.authorization.admin.provisioning.IdentityLinkOutcome;
import edu.bu.archive.application.authorization.admin.provisioning.IdentityLinkRequest;

/**
 * Operator entry point for linking a Cognito identity to an archive identity.
 *
 * <p><strong>Not an HTTP endpoint, and it cannot become one by accident.</strong>
 * An {@link ApplicationRunner} behind the {@code link-identity} profile, which
 * no deployment sets. Running it is a deliberate act: a one-off task with that
 * profile and {@code spring.main.web-application-type=none}, so no port is
 * opened while it holds the ability to decide who a signed-in person is.
 *
 * <p>Every value is a flag the operator types. Nothing is read from a token or
 * looked up in Cognito - the subject must be fetched out of band and pasted
 * in. The default is a dry run: writing requires {@code --confirm}.
 *
 * <pre>
 * --aws-account-id=770203350335
 * --environment=dev
 * --cognito-user-pool-id=us-east-1_XXXXXXXXX
 * --cognito-region=us-east-1
 * --cognito-issuer=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_XXXXXXXXX
 * --cognito-subject=&lt;the immutable sub UUID&gt;
 * --subject-identifier=&lt;the ARP being linked&gt;
 * --actor=&lt;the acting administrator's ARP&gt;
 * [--kuali-person-id=&lt;numeric PERSON_ID&gt;]   required only for CONTACT_DERIVATION
 * [--login-name=&lt;display only&gt;]
 * --operator=&lt;the person running this&gt;
 * --reason="..."
 * --approval-reference="..."
 * [--confirm]                                 without it, this is a dry run
 * </pre>
 *
 * <p>To withdraw a link instead, pass its immutable id and nothing
 * link-shaped:
 *
 * <pre>
 * --unlink-link-id=&lt;identity_link_id&gt;
 * --actor=&lt;the acting administrator's ARP&gt;
 * --operator=... --reason=... --approval-reference=...
 * [--confirm]
 * </pre>
 *
 * <p>Unlinking is a status change, never a delete: the row keeps its history
 * and an IDENTITY_UNLINKED audit row is written in the same transaction. It
 * does NOT revoke the person's grants - those hang off the archive identity
 * and are withdrawn separately, through the grant utility.
 *
 * <p>This is the supported way to establish an identity while the
 * administrative API is unreachable, which it is whenever enforcement is off -
 * the same relationship grant provisioning has to the grant endpoints.
 *
 * <p>Exit status is 0 for a dry run that passed and for a completed link, and
 * 1 for a refusal, so a wrapper script can branch on it.
 */
@Component
@Profile("link-identity")
public class IdentityLinkProvisioningCli implements ApplicationRunner {

    private final ArchiveAdminService admin;
    private final AdminRoleRepository adminRoles;
    private final AdminIdentityRepository identities;

    public IdentityLinkProvisioningCli(
            ArchiveAdminService admin, AdminRoleRepository adminRoles,
            AdminIdentityRepository identities) {
        this.admin = admin;
        this.adminRoles = adminRoles;
        this.identities = identities;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, String> options = new HashMap<>();
        for (String name : args.getOptionNames()) {
            options.put(name, args.getOptionValues(name).stream().findFirst().orElse(null));
        }

        IdentityLinkRequest request = new IdentityLinkRequest(
                options.get("aws-account-id"),
                options.get("environment"),
                options.get("cognito-user-pool-id"),
                options.get("cognito-region"),
                options.get("cognito-issuer"),
                options.get("cognito-subject"),
                options.get("subject-identifier"),
                options.get("actor"),
                options.get("kuali-person-id"),
                options.get("login-name"),
                options.get("operator"),
                options.get("reason"),
                options.get("approval-reference"),
                args.containsOption("dry-run"),
                args.containsOption("confirm"),
                options.get("unlink-link-id"));

        IdentityLinkOutcome outcome = new IdentityLinkCommand(
                options.getOrDefault("environment", ""),
                admin, adminRoles, identities,
                // Read from the ECS task metadata endpoint, NOT from the
                // flags - the flags are what we are checking.
                new EcsTaskMetadataRuntimeIdentity()).run(request);

        print(outcome);
        if (outcome.result() == IdentityLinkOutcome.Result.REFUSED) {
            throw new IllegalStateException(outcome.message());
        }
    }

    /*
     * System.out rather than a logger, matching the other two utilities: a
     * console tool read by a person. Identifiers are masked in every line the
     * command returns, and no token or password is in scope.
     */
    private static void print(IdentityLinkOutcome outcome) {
        System.out.println();
        System.out.println("Research Archive identity linking - " + outcome.result());
        outcome.checks().forEach(line -> System.out.println("  " + line));
        outcome.problems().forEach(line -> System.out.println("  FAIL   " + line));
        System.out.println();
        System.out.println(outcome.message());
        if (outcome.wrote()) {
            System.out.println();
            System.out.println("Audit evidence - keep this:");
            System.out.println("  SELECT identity_link_id, institutional_identifier, method,"
                    + " kuali_person_id, status, verified_by, verified_at"
                    + " FROM authz.identity_link;");
            System.out.println("  SELECT actor, action, occurred_at, detail ->> 'note'"
                    + " FROM authz.access_audit ORDER BY audit_id;");
        }
        System.out.println();
    }
}
