package edu.bu.archive.adapter.in.cli;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.admin.provisioning.GrantOutcome;
import edu.bu.archive.application.authorization.admin.provisioning.GrantProvisioningCommand;
import edu.bu.archive.application.authorization.admin.provisioning.GrantRequest;

/**
 * Operator entry point for issuing the first research-data grants.
 *
 * <p><strong>Not an HTTP endpoint, and it cannot become one by accident.</strong>
 * An {@link ApplicationRunner} behind the {@code grant-admin} profile, which
 * no deployment sets. Running it is a deliberate act: a one-off task with
 * that profile and {@code spring.main.web-application-type=none}, so no port
 * is opened while it holds the ability to change who can see research data.
 *
 * <p>Every value is a flag the operator types. Nothing is read from a token,
 * looked up in Cognito, or inferred from an email address. The default is a
 * dry run: writing requires {@code --confirm}, so forgetting a flag produces
 * the safe outcome.
 *
 * <pre>
 * --aws-account-id=770203350335
 * --environment=dev
 * --actor=&lt;the acting administrator's ARP&gt;
 * --grantee=&lt;the ARP receiving access&gt;
 * --type=CENTRAL|UNIT|IO|CONTACT_DERIVATION
 * --unit-number=&lt;n&gt; [--include-descendants]   (UNIT only)
 * --io-value=&lt;v&gt;                              (IO only)
 * --operator=&lt;the person running this&gt;
 * --reason="..."
 * --approval-reference="..."
 * [--confirm]                                 without it, this is a dry run
 * </pre>
 *
 * <p>Exit status is 0 for a dry run that passed and for a completed grant,
 * and 1 for a refusal, so a wrapper script can branch on it.
 */
@Component
@Profile("grant-admin")
public class GrantProvisioningCli implements ApplicationRunner {

    private final ArchiveAdminService admin;
    private final AdminRoleRepository adminRoles;
    private final AccessGrantRepository grants;

    public GrantProvisioningCli(
            ArchiveAdminService admin, AdminRoleRepository adminRoles,
            AccessGrantRepository grants) {
        this.admin = admin;
        this.adminRoles = adminRoles;
        this.grants = grants;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, String> options = new HashMap<>();
        for (String name : args.getOptionNames()) {
            options.put(name, args.getOptionValues(name).stream().findFirst().orElse(null));
        }

        GrantRequest request = new GrantRequest(
                options.get("aws-account-id"),
                options.get("environment"),
                options.get("grantee"),
                options.get("actor"),
                options.get("type"),
                options.get("unit-number"),
                args.containsOption("include-descendants"),
                options.get("io-value"),
                options.get("operator"),
                options.get("reason"),
                options.get("approval-reference"),
                args.containsOption("dry-run"),
                args.containsOption("confirm"));

        GrantOutcome outcome = new GrantProvisioningCommand(
                options.getOrDefault("aws-account-id", ""),
                options.getOrDefault("environment", ""),
                admin, adminRoles, grants).run(request);

        print(outcome);
        if (outcome.result() == GrantOutcome.Result.REFUSED) {
            throw new IllegalStateException(outcome.message());
        }
    }

    /*
     * System.out rather than a logger, deliberately, matching the bootstrap
     * utility: a console tool read by a person. Identifiers are masked in
     * every line the command returns, and no token or password is in scope.
     */
    private static void print(GrantOutcome outcome) {
        System.out.println();
        System.out.println("Research Archive grant provisioning - " + outcome.result());
        outcome.checks().forEach(line -> System.out.println("  " + line));
        outcome.problems().forEach(line -> System.out.println("  FAIL   " + line));
        System.out.println();
        System.out.println(outcome.message());
        if (outcome.wrote()) {
            System.out.println();
            System.out.println("Audit evidence - keep this:");
            System.out.println("  SELECT grant_id, grantee_identifier, grant_type, unit_number,"
                    + " io_value, granted_by, granted_at FROM authz.access_grant;");
            System.out.println("  SELECT actor, action, occurred_at, detail ->> 'note'"
                    + " FROM authz.access_audit ORDER BY audit_id;");
        }
        System.out.println();
    }
}
