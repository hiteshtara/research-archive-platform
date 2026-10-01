package edu.bu.archive.adapter.in.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import edu.bu.archive.application.authorization.AuthorizationPathNotScopedException;
import edu.bu.archive.application.authorization.RecordAuthorizationService;

/**
 * Record-level authorization for every /api request, BEFORE the controller
 * runs (so no section query executes for an out-of-scope record). No-op when
 * enforcement is off ("record authorization not enforced").
 *
 * <p>With enforcement on:
 * <ul>
 *   <li>an unprovisioned or denied identity is refused everywhere except the
 *       access-status endpoint;</li>
 *   <li>Central users may use every path;</li>
 *   <li>everyone else may use ONLY the paths listed here, which are scoped in
 *       SQL or checked record by record. Every other path - not yet brought
 *       under record authorization - is refused with
 *       NOT_AVAILABLE_UNDER_RECORD_AUTHORIZATION, so an unfinished path can
 *       never leak an out-of-scope record.</li>
 * </ul>
 * See docs/architecture/RECORD_AUTHORIZATION_STATUS.md for the path list.
 */
public class RecordAuthorizationInterceptor implements HandlerInterceptor {

    private static final Pattern AWARD_ID = Pattern.compile("^/api/v1/awards/(\\d+)(/.*)?$");
    private static final Pattern AWARD_BY_NUMBER = Pattern.compile("^/api/v1/awards/by-number/([^/]+)$");
    private static final Pattern AWARD_HIERARCHY = Pattern.compile("^/api/v1/awards/([^/]+)/hierarchy$");
    private static final Pattern PROPOSAL_ID = Pattern.compile("^/api/v1/proposals/(\\d+)(/.*)?$");
    private static final Pattern PROPOSAL_NUMBER = Pattern.compile("^/api/proposals/([^/]+)(/history|/awards)?$");

    /** Award sub-paths whose responses would include related or other-version records not yet filtered. */
    private static final Pattern AWARD_UNFILTERED_SUBPATH =
            Pattern.compile("^/(report\\.pdf|report-with-attachments\\.pdf)$");

    private final RecordAuthorizationService authorization;

    public RecordAuthorizationInterceptor(RecordAuthorizationService authorization) {
        this.authorization = authorization;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!authorization.enforced()) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/") || path.equals("/api/v1/me/access")) {
            return true;
        }
        authorization.requireProvisioned();
        if (authorization.unrestricted()) {
            return true;
        }

        switch (path) {
            case "/api/v1/awards/search", "/api/v1/awards/versions/search",
                 "/api/proposals/search", "/api/global-search", "/api/dashboard" -> {
                return true;   // scoped inside the query
            }
            default -> { }
        }

        Matcher m;
        if ((m = AWARD_BY_NUMBER.matcher(path)).matches()) {
            authorization.requireAwardNumber(m.group(1));
            return true;
        }
        if ((m = AWARD_HIERARCHY.matcher(path)).matches()) {
            authorization.requireAwardNumber(m.group(1));
            return true;       // the service prunes out-of-scope nodes
        }
        if ((m = AWARD_ID.matcher(path)).matches()) {
            authorization.requireAward(Long.parseLong(m.group(1)));
            String sub = m.group(2) == null ? "" : m.group(2);
            if (AWARD_UNFILTERED_SUBPATH.matcher(sub).matches()) {
                throw new AuthorizationPathNotScopedException();
            }
            return true;
        }
        if ((m = PROPOSAL_ID.matcher(path)).matches()) {
            authorization.requireProposal(Long.parseLong(m.group(1)));
            return true;
        }
        if ((m = PROPOSAL_NUMBER.matcher(path)).matches() && !"search".equals(m.group(1))
                && !"families".equals(m.group(1))) {
            authorization.requireProposalNumber(m.group(1));
            return true;
        }
        throw new AuthorizationPathNotScopedException();
    }
}
