package edu.bu.archive.adapter.in.cli;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.admin.bootstrap.ArchiveAdminBootstrapCommand;
import edu.bu.archive.application.authorization.admin.bootstrap.BootstrapOutcome;
import edu.bu.archive.application.authorization.admin.bootstrap.BootstrapRequest;
import edu.bu.archive.application.authorization.admin.bootstrap.BootstrapTarget;

/**
 * Operator entry point for the one-time ARCHIVE_ADMIN bootstrap.
 *
 * <p><strong>Not an HTTP endpoint, and it cannot become one by accident.</strong>
 * It is an {@link ApplicationRunner} behind the {@code bootstrap-admin}
 * profile, which no deployment sets. Running it is a deliberate act: a
 * one-off task with that profile active and
 * {@code spring.main.web-application-type=none}, so no port is even opened
 * while it runs.
 *
 * <p>Every value is a flag the operator types. Nothing is read from a token,
 * looked up in Cognito, or inferred. The default is a dry run: writing
 * requires {@code --confirm}, so forgetting a flag produces the safe outcome.
 *
 * <pre>
 * --aws-account-id=770203350335
 * --environment=dev
 * --cognito-user-pool-id=us-east-1_XXXXXXXXX
 * --cognito-issuer=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_XXXXXXXXX
 * --cognito-subject=&lt;the immutable sub UUID&gt;
 * --institutional-identifier=&lt;the approved value&gt;
 * --login-name=&lt;display only, optional&gt;
 * --operator=&lt;the person running this&gt;
 * --reason="Approved by ... on ..."
 * [--accept-provisional-identifier]   when the identifier is email-shaped
 * [--confirm]                         without it, this is a dry run
 * </pre>
 *
 * <p>The exit status is 0 for a dry run that passed and for a completed
 * bootstrap, and 1 for a refusal, so a wrapper script can branch on it.
 */
@Component
@Profile("bootstrap-admin")
public class ArchiveAdminBootstrapCli implements ApplicationRunner {

    private final ArchiveAdminService admin;
    private final AdminRoleRepository adminRoles;
    private final AdminIdentityRepository identities;
    private final AuthorizationProperties properties;

    public ArchiveAdminBootstrapCli(
            ArchiveAdminService admin,
            AdminRoleRepository adminRoles,
            AdminIdentityRepository identities,
            AuthorizationProperties properties) {
        this.admin = admin;
        this.adminRoles = adminRoles;
        this.identities = identities;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, String> options = read(args);

        BootstrapTarget target = new BootstrapTarget(
                options.get("aws-account-id"),
                options.get("environment"),
                options.get("cognito-user-pool-id"),
                options.getOrDefault("cognito-region", "us-east-1"));

        BootstrapRequest request = new BootstrapRequest(
                options.get("aws-account-id"),
                options.get("environment"),
                options.get("cognito-user-pool-id"),
                options.get("cognito-issuer"),
                options.get("cognito-subject"),
                options.get("institutional-identifier"),
                options.get("login-name"),
                options.get("operator"),
                options.get("reason"),
                args.containsOption("accept-provisional-identifier"),
                args.containsOption("dry-run"),
                args.containsOption("confirm"));

        BootstrapOutcome outcome = new ArchiveAdminBootstrapCommand(
                target, admin, adminRoles, identities, properties).run(request);

        print(outcome);
        if (outcome.result() == BootstrapOutcome.Result.REFUSED) {
            throw new IllegalStateException(outcome.message());
        }
    }

    /*
     * System.out rather than a logger, deliberately. This is a console tool
     * run by a person reading its output; routing it through application
     * logging would put identifiers into log aggregation for no benefit. The
     * command masks identifiers in every line it returns, and no token or
     * password is ever in scope here.
     */
    private static void print(BootstrapOutcome outcome) {
        System.out.println();
        System.out.println("ARCHIVE_ADMIN bootstrap - " + outcome.result());
        outcome.checks().forEach(line -> System.out.println("  " + line));
        outcome.problems().forEach(line -> System.out.println("  FAIL   " + line));
        System.out.println();
        System.out.println(outcome.message());
        if (outcome.wrote()) {
            System.out.println();
            System.out.println("Audit evidence - keep this:");
            System.out.println("  SELECT institutional_identifier, status, granted_by, granted_at,"
                    + " reason FROM authz.admin_role_member;");
            System.out.println("  SELECT actor, action, occurred_at FROM authz.access_audit"
                    + " ORDER BY audit_id;");
        }
        System.out.println();
    }

    private static Map<String, String> read(ApplicationArguments args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String name : args.getOptionNames()) {
            options.put(name, args.getOptionValues(name).stream()
                    .findFirst()
                    .map(String::trim)
                    .orElse(null));
        }
        // Positional arguments are never read: every value must be a named
        // flag, so an argument cannot land in the wrong field by position.
        if (!Arrays.asList(args.getSourceArgs()).isEmpty() && args.getNonOptionArgs().size() > 0) {
            throw new IllegalArgumentException(
                    "positional arguments are not accepted; use named --flags only");
        }
        return options;
    }
}
