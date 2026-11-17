package edu.bu.archive.adapter.in.cli;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.bootstrap.BootstrapOutcome;
import edu.bu.archive.application.authorization.admin.bootstrap.BootstrapTarget;
import edu.bu.archive.application.authorization.admin.bootstrap.SecondAdministratorBootstrapCommand;
import edu.bu.archive.application.authorization.admin.bootstrap.SecondAdministratorBootstrapRequest;
import edu.bu.archive.application.authorization.admin.bootstrap.SecondAdministratorBootstrapService;
import edu.bu.archive.application.authorization.admin.provisioning.EcsTaskMetadataRuntimeIdentity;

/**
 * Operator entry point for the one-time SECOND-administrator bootstrap
 * (A-category design 11.1).
 *
 * <p>Not an HTTP endpoint. An {@link ApplicationRunner} behind the
 * {@code bootstrap-second-admin} profile, which no deployment sets; run as a
 * one-off task with that profile and {@code spring.main.web-application-type=none}.
 *
 * <pre>
 * --aws-account-id=&lt;account&gt;
 * --environment=dev
 * --cognito-user-pool-id=us-east-1_XXXXXXXXX
 * --cognito-issuer=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_XXXXXXXXX
 * --cognito-subject=&lt;the new administrator's immutable sub UUID&gt;
 * --mint-archive-identifier           OR --institutional-identifier=ARP-...
 * --login-name=&lt;display only, optional&gt;
 * --operator=&lt;the person running this&gt;
 * --reason="&lt;why, at least two words&gt;"
 * --change-reference=&lt;the approved change or ticket&gt;
 * [--execute]                         without it, this is a dry run
 * </pre>
 *
 * <p>Exits 0 for a passing dry run or a completed seating, non-zero for a refusal.
 * NOT run against any shared environment by this change.
 */
@Component
@Profile("bootstrap-second-admin")
public class SecondAdministratorBootstrapCli implements ApplicationRunner {

    private final SecondAdministratorBootstrapService service;
    private final AdminRoleRepository adminRoles;
    private final AdminIdentityRepository identities;

    public SecondAdministratorBootstrapCli(SecondAdministratorBootstrapService service,
                                           AdminRoleRepository adminRoles,
                                           AdminIdentityRepository identities) {
        this.service = service;
        this.adminRoles = adminRoles;
        this.identities = identities;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().isEmpty()) {
            throw new IllegalArgumentException(
                    "positional arguments are not accepted; use named --flags only");
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (String name : args.getOptionNames()) {
            options.put(name, args.getOptionValues(name).stream()
                    .findFirst().map(String::trim).orElse(null));
        }

        BootstrapTarget target = new BootstrapTarget(
                options.get("aws-account-id"),
                options.get("environment"),
                options.get("cognito-user-pool-id"),
                options.getOrDefault("cognito-region", "us-east-1"));

        SecondAdministratorBootstrapRequest request = new SecondAdministratorBootstrapRequest(
                options.get("aws-account-id"),
                options.get("environment"),
                options.get("cognito-user-pool-id"),
                options.get("cognito-issuer"),
                options.get("cognito-subject"),
                options.get("institutional-identifier"),
                args.containsOption("mint-archive-identifier"),
                options.get("login-name"),
                options.get("operator"),
                options.get("reason"),
                options.get("change-reference"),
                args.containsOption("execute"));

        BootstrapOutcome outcome = new SecondAdministratorBootstrapCommand(
                target, service, adminRoles, identities)
                .withRuntime(new EcsTaskMetadataRuntimeIdentity())
                .run(request);

        // System.out, not a logger: a console tool read by a person; lines are masked.
        System.out.println();
        System.out.println("Second ARCHIVE_ADMIN bootstrap - " + outcome.result());
        outcome.checks().forEach(line -> System.out.println("  " + line));
        outcome.problems().forEach(line -> System.out.println("  FAIL   " + line));
        System.out.println();
        System.out.println(outcome.message());
        System.out.println();
        if (outcome.result() == BootstrapOutcome.Result.REFUSED) {
            throw new IllegalStateException(outcome.message());
        }
    }
}
