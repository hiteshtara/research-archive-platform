package edu.bu.archive.adapter.in.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import edu.bu.archive.application.authorization.AuthorizationPathNotScopedException;
import edu.bu.archive.application.authorization.RecordAuthorizationService;
import edu.bu.archive.application.authorization.RecordNotAccessibleException;

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
 *       never leak an out-of-scope record. Award and Proposal sub-paths are
 *       explicit allow-lists: a NEW sub-endpoint is closed until it is
 *       reviewed and added here.</li>
 * </ul>
 * See docs/architecture/RECORD_AUTHORIZATION_STATUS.md for the path list.
 */
public class RecordAuthorizationInterceptor implements HandlerInterceptor {

    private static final Pattern AWARD_ID = Pattern.compile("^/api/v1/awards/(\\d+)(/.*)?$");
    private static final Pattern AWARD_BY_NUMBER = Pattern.compile("^/api/v1/awards/by-number/([^/]+)$");
    private static final Pattern AWARD_HIERARCHY = Pattern.compile("^/api/v1/awards/([^/]+)/hierarchy$");
    private static final Pattern PROPOSAL_ID = Pattern.compile("^/api/v1/proposals/(\\d+)(/.*)?$");
    private static final Pattern PROPOSAL_NUMBER = Pattern.compile("^/api/proposals/([^/]+)(/history|/awards)?$");
    private static final Pattern AI_AWARD = Pattern.compile("^/api/ai/awards/([^/]+)/(summary|questions|evidence-search)$");

    /**
     * Award sub-paths whose services filter every other-version, other-family
     * and related row for restricted callers (AwardArchiveService). The
     * reports are built from those same service methods. Anything else under
     * /api/v1/awards/{id} is refused.
     */
    static final Pattern AWARD_SCOPED_SUBPATH = Pattern.compile("^("
            + "|/summary|/versions|/people|/unit-details|/unit-contacts|/sponsor-contacts"
            + "|/central-administration-contacts|/amounts"
            + "|/time-and-money/summary|/time-and-money/actions|/time-and-money/history"
            + "|/time-and-money/transactions/\\d+|/time-and-money/documents/[^/;]+"
            + "|/terms|/custom-data|/comments|/sap-transmissions"
            + "|/attachments|/attachments/\\d+/download"
            + "|/report\\.pdf|/report-with-attachments\\.pdf"
            + "|/budget/summary|/budget/versions|/budget/periods|/budget/line-items|/budget/personnel"
            + "|/funding-proposals|/funding-subawards|/negotiations"
            + ")$");

    /**
     * Proposal sub-paths (ProposalArchiveV1Service): version-scoped by
     * proposal_id, or - versions, comments, funded-awards - filtered per
     * version for restricted callers. Anything else under
     * /api/v1/proposals/{id} is refused.
     */
    static final Pattern PROPOSAL_SCOPED_SUBPATH = Pattern.compile("^("
            + "|/versions|/people|/units|/attachments|/attachments/\\d+/download"
            + "|/comments|/funded-awards|/custom-data"
            + ")$");

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
            case "/api/v1/documents", "/api/documents/search" -> {
                // Document Explorer and document search: Award and Proposal documents scoped in
                // SQL per module branch (DocumentRecordScope); Negotiation, Subaward and IRB
                // documents excluded for restricted callers until their rule is decided.
                return true;
            }
            case "/api/v1/attachments/search" -> {
                // Archived File Finder: Award rows scoped in SQL, other
                // modules omitted (AttachmentSearchService); the
                // ArchiveAttachmentViewer check stays in the controller.
                return true;
            }
            case "/api/v1/explorer/awards" -> {
                authorization.requireAwardNumber(singleParameter(request, "awardNumber"));
                return true;   // returns only the current version, the one just checked
            }
            case "/api/v1/explorer/award-versions" -> {
                authorization.requireAward(parseId(singleParameter(request, "awardId")));
                return true;
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
            String sub = m.group(2) == null ? "" : m.group(2);
            if (!AWARD_SCOPED_SUBPATH.matcher(sub).matches()) {
                throw new AuthorizationPathNotScopedException();
            }
            authorization.requireAward(Long.parseLong(m.group(1)));
            return true;
        }
        if ((m = AI_AWARD.matcher(path)).matches()) {
            // The AI context spans every version of the family (policy P3).
            authorization.requireEveryAwardVersion(m.group(1));
            return true;
        }
        if ((m = PROPOSAL_ID.matcher(path)).matches()) {
            String sub = m.group(2) == null ? "" : m.group(2);
            if (!PROPOSAL_SCOPED_SUBPATH.matcher(sub).matches()) {
                throw new AuthorizationPathNotScopedException();
            }
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

    /** Exactly one non-blank value, or the request is refused like a missing record. */
    private static String singleParameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length != 1 || values[0] == null || values[0].isBlank()) {
            throw new RecordNotAccessibleException();
        }
        return values[0];
    }

    private static long parseId(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException invalid) {
            throw new RecordNotAccessibleException();
        }
    }
}
