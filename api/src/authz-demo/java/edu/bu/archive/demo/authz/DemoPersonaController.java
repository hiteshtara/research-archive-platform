package edu.bu.archive.demo.authz;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** DEMO BUILD ONLY: the synthetic personas for the UI selector. */
@RestController
@Profile("authz-demo")
public class DemoPersonaController {

    private final JdbcClient jdbc;

    public DemoPersonaController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Persona(String key, String label, boolean attachmentViewer, String description) {
    }

    @GetMapping("/demo/personas")
    public List<Persona> personas() {
        return jdbc.sql("SELECT persona_key, label, attachment_viewer, description FROM authz_demo.persona ORDER BY sort_order")
                .query((rs, i) -> new Persona(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4)))
                .list();
    }
}
