package edu.bu.archive.authorization;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The synthetic-identity demo (src/authz-demo) is compiled only with
 * -Pauthz-demo. CI and the deployable image use the default build, so the
 * demo identity shortcut must not be on this classpath.
 */
class DemoClassesAbsentFromDefaultBuildTest {

    @Test
    void theDemoIdentityShortcutIsNotInTheDefaultBuild() {
        for (String name : new String[] {
                "edu.bu.archive.demo.authz.AuthzDemoConfiguration",
                "edu.bu.archive.demo.authz.DemoPersonaFilter",
                "edu.bu.archive.demo.authz.DemoPersonaController"}) {
            assertThatThrownBy(() -> Class.forName(name)).isInstanceOf(ClassNotFoundException.class);
        }
        assertThatThrownBy(() -> {
            if (getClass().getClassLoader().getResource("application-authz-demo.yml") != null) {
                throw new IllegalStateException("demo profile config is on the default classpath");
            }
            throw new ClassNotFoundException("absent");
        }).isInstanceOf(ClassNotFoundException.class);
    }
}
