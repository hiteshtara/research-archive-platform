package edu.bu.archive.application.award.report;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.application.award.AwardArchiveService;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/*
 * Gathers every Complete Award Report section by calling
 * AwardArchiveService's existing, already-correct methods with the
 * report's target awardId - the same methods and the same scoping
 * rules (family-wide vs. this-exact-version) the Award workspace UI
 * itself already relies on. No new business/scoping logic is
 * introduced here; see the Award Report design note in
 * AwardReportData's Javadoc for which sections are family-wide vs.
 * version-scoped.
 *
 * Paginated sections are walked page-by-page at the same
 * PaginationSupport page size the rest of the app already trusts
 * (REPORT_PAGE_SIZE), rather than issuing one unbounded query -
 * bounding memory to realistic Award family sizes without inventing a
 * second pagination scheme.
 */
@Component
public class AwardReportService {

    private static final int REPORT_PAGE_SIZE = 100;

    private final AwardArchiveService service;

    public AwardReportService(AwardArchiveService service) {
        this.service = service;
    }

    public AwardReportData buildReportData(long awardId) {
        // findSummary throws NoSuchElementException for an unknown
        // awardId, which GlobalExceptionHandler already maps to 404 -
        // no separate not-found handling needed here.
        var summary = service.findSummary(awardId);

        return new AwardReportData(
                summary,
                allPages(page -> service.findVersions(awardId, page, REPORT_PAGE_SIZE)),
                service.findPeople(awardId),
                service.findFundingProposals(awardId),
                service.findFundingSubawards(awardId),
                service.findAssociatedNegotiations(awardId),
                allPages(page -> service.findAmounts(awardId, page, REPORT_PAGE_SIZE)),
                service.findBudgetSummary(awardId),
                allPages(page -> service.findBudgetVersions(awardId, page, REPORT_PAGE_SIZE)),
                service.findBudgetPeriods(awardId),
                allPages(page -> service.findBudgetLineItems(awardId, page, REPORT_PAGE_SIZE)),
                allPages(page -> service.findBudgetPersonnel(awardId, page, REPORT_PAGE_SIZE)),
                service.findTimeAndMoneySummary(awardId),
                allPages(page -> service.findTimeAndMoneyActions(awardId, page, REPORT_PAGE_SIZE)),
                allPages(page -> service.findTimeAndMoneyHistory(awardId, page, REPORT_PAGE_SIZE)),
                service.findTerms(awardId),
                service.findCustomData(awardId),
                service.findComments(awardId),
                allPages(page -> service.findSapTransmissions(awardId, page, REPORT_PAGE_SIZE)),
                Instant.now()
        );
    }

    private static <T> List<T> allPages(IntFunction<PageResponse<T>> fetchPage) {
        List<T> all = new ArrayList<>();
        int page = 0;
        while (true) {
            PageResponse<T> response = fetchPage.apply(page);
            all.addAll(response.content());
            if (response.last() || response.content().isEmpty()) {
                break;
            }
            page++;
        }
        return all;
    }
}
