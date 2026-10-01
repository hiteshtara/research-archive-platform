package edu.bu.archive.demo.authz;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/**
 * DEMO BUILD ONLY. The UI's demo sign-in sends "Bearer demo-persona:KEY".
 * This filter turns that into the persona's SYNTHETIC Cognito identity
 * (demo issuer + "demo-KEY"), which then goes through the REAL identity
 * links and grants in the demo database. A persona flagged in
 * authz_demo.persona also gets the ArchiveAttachmentViewer role, so the
 * existing attachment gate is exercised unchanged.
 */
public class DemoPersonaFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer demo-persona:";
    private static final Pattern KEY = Pattern.compile("^[a-z0-9-]{1,40}$");
    private static final String IDENTITY_ATTRIBUTE = DemoPersonaFilter.class.getName() + ".identity";

    private final JdbcClient jdbc;

    DemoPersonaFilter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static Optional<ValidatedCognitoIdentity> currentIdentity() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object identity = attributes == null ? null
                : attributes.getAttribute(IDENTITY_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return Optional.ofNullable((ValidatedCognitoIdentity) identity);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            String key = header.substring(PREFIX.length()).trim();
            if (KEY.matcher(key).matches()) {
                // The persona header has no sign-in: each request counts as signed in now, so the
                // maximum sign-in age never applies here. The identity lab tests it with real logins.
                request.setAttribute(IDENTITY_ATTRIBUTE,
                        new ValidatedCognitoIdentity(AuthzDemoConfiguration.DEMO_ISSUER, "demo-" + key, null,
                                java.time.Instant.now()));
                boolean attachmentViewer = Boolean.TRUE.equals(jdbc.sql(
                                "SELECT attachment_viewer FROM authz_demo.persona WHERE persona_key = :key")
                        .param("key", key).query(Boolean.class).optional().orElse(false));
                var authorities = attachmentViewer
                        ? List.of(new SimpleGrantedAuthority("ROLE_ArchiveAttachmentViewer"))
                        : List.<SimpleGrantedAuthority>of();
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken("demo-" + key, null, authorities));
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
