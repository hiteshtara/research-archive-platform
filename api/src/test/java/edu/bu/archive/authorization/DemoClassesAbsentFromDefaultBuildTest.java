package edu.bu.archive.authorization;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The synthetic-identity demo and the local SAML lab's API side (src/authz-demo)
 * are compiled only with
 * -Pauthz-demo. CI and the deployable image use the default build, so the
 * demo identity shortcut must not be on this classpath.
 */
class DemoClassesAbsentFromDefaultBuildTest {

    @Test
    void theDemoIdentityShortcutIsNotInTheDefaultBuild() {
        for (String name : new String[] {
                "edu.bu.archive.demo.authz.AuthzDemoConfiguration",
                "edu.bu.archive.demo.authz.DemoPersonaFilter",
                "edu.bu.archive.demo.authz.DemoPersonaController",
                "edu.bu.archive.demo.authz.SyntheticIo",
                "edu.bu.archive.demo.identitylab.IdentityLabConfiguration"}) {
            assertThatThrownBy(() -> Class.forName(name)).isInstanceOf(ClassNotFoundException.class);
        }
        assertThatThrownBy(() -> {
            for (String config : new String[] {"application-authz-demo.yml", "application-identity-lab.yml"}) {
                if (getClass().getClassLoader().getResource(config) != null) {
                    throw new IllegalStateException(config + " is on the default classpath");
                }
            }
            throw new ClassNotFoundException("absent");
        }).isInstanceOf(ClassNotFoundException.class);
    }
}
