package edu.bu.archive.application.subaward;

import edu.bu.archive.adapter.out.persistence.SubawardSemanticSummaryRow;

import edu.bu.archive.adapter.in.web.dto.subaward.SubawardAmountResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardCloseoutResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardContactResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardFundingResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardNotepadResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardPageResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardReportResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardRowResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardTemplateInfoResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardVersionSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardWorkspaceResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.PaginationSupport;
import edu.bu.archive.adapter.out.persistence.SubawardArchiveRepository;
import edu.bu.archive.adapter.out.persistence.SubawardArchivedAttachment;
import edu.bu.archive.adapter.out.persistence.SubawardAttachmentStorage;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class SubawardArchiveService {

    private final SubawardArchiveRepository repository;
    private final edu.bu.archive.application.authorization.SubawardRecordAccess recordAccess;
    private final SubawardAttachmentStorage attachmentStorage;

    /*
     * Pre-Phase-2 signature, kept so the existing unit tests construct this
     * service unchanged. It resolves to PERMIT_ALL, which is exactly what
     * every caller got before record authorization existed, and it is never
     * the constructor Spring uses - the @Autowired one below is, so a
     * deployed context always goes through the startup refusal.
     */
    public SubawardArchiveService(
            SubawardArchiveRepository repository,
            SubawardAttachmentStorage attachmentStorage
    ) {
        this(repository, attachmentStorage, null, null);
    }

    /** Direct-injection form, for tests that already hold the port. */
    public SubawardArchiveService(
            SubawardArchiveRepository repository,
            SubawardAttachmentStorage attachmentStorage,
            edu.bu.archive.application.authorization.SubawardRecordAccess recordAccess
    ) {
        this.recordAccess = recordAccess == null
                ? edu.bu.archive.application.authorization.SubawardRecordAccess.PERMIT_ALL
                : recordAccess;
        this.repository = repository;
        this.attachmentStorage = attachmentStorage;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SubawardArchiveService(
            SubawardArchiveRepository repository,
            SubawardAttachmentStorage attachmentStorage,
            org.springframework.beans.factory.ObjectProvider<
                    edu.bu.archive.application.authorization.SubawardRecordAccess> recordAccessProvider,
            org.springframework.beans.factory.ObjectProvider<
                    edu.bu.archive.application.authorization.AuthorizationProperties>
                    authorizationProperties
    ) {
        edu.bu.archive.application.authorization.SubawardRecordAccess resolvedAccess =
                recordAccessProvider == null ? null : recordAccessProvider.getIfAvailable();
        this.recordAccess = resolvedAccess != null
                ? resolvedAccess
                : edu.bu.archive.application.authorization.SubawardRecordAccess.PERMIT_ALL;
        edu.bu.archive.application.authorization.SubawardRecordAccess
                .requireEnforceableWiring(this.recordAccess, authorizationProperties);
        this.repository = repository;
        this.attachmentStorage = attachmentStorage;
    }

    public SubawardPageResponse findPage(
            String query,
            int page,
            int size
    ) {
        return findPage(query, SubawardSearchFilters.none(), page, size);
    }

    /*
     * Structured filters AND with the free-text query (and its FRN
     * branch) in SQL, so paging and totalElements describe the complete
     * filtered result set. Filters alone are a valid search.
     */
    public SubawardPageResponse findPage(
            String query,
            SubawardSearchFilters filters,
            int page,
            int size
    ) {
        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        /*
         * Resolved ONCE and given to both statements. The predicate is a
         * database-level EXISTS over the Subaward's funding Awards, so the
         * count, the page metadata and the returned rows describe one row
         * set. Filtering the fetched page in Java instead would publish the
         * out-of-scope Subawards through totalElements and hand back short
         * pages that look like the end of the result.
         */
        recordAccess.requireProvisioned();

        edu.bu.archive.application.authorization.SqlFragment scope =
                recordAccess.subawardScopeSql("s");

        long totalElements = repository.countSubawards(query, filters, scope);
        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(
                        safePage,
                        safeSize,
                        totalElements
                );
        int offset = safePage * safeSize;

        List<SubawardSummaryResponse> content =
                repository.findSubawards(
                        query,
                        filters,
                        scope,
                        safeSize,
                        offset
                );

        return new SubawardPageResponse(
                content,
                safePage,
                safeSize,
                totalElements,
                pageMetadata.totalPages(),
                pageMetadata.first(),
                pageMetadata.last()
        );
    }

    public SubawardWorkspaceResponse findWorkspace(long subawardId) {
        recordAccess.requireSubaward(subawardId);
        SubawardRowResponse current = requireSubaward(subawardId);
        return new SubawardWorkspaceResponse(subawardId, current);
    }

    /*
     * Mirrors AwardArchiveService.findVersions exactly: resolve the
     * family (subawardCode) first, then paginate every version within
     * it. requireSubaward already fetches the full row (including
     * subawardCode) as its own existence/positive-ID check, so - unlike
     * Award, which has a dedicated lightweight findAwardNumberForId
     * query - no separate repository call is needed just to resolve the
     * family here.
     */
    public PageResponse<SubawardVersionSummaryResponse> findVersions(
            long subawardId,
            int page,
            int size
    ) {
        recordAccess.requireSubaward(subawardId);
        SubawardRowResponse current = requireSubaward(subawardId);
        String subawardCode = current.subawardCode();

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        /*
         * Each version is decided on its own, not inherited from the one the
         * caller opened. Versions of a Subaward family are separate
         * subaward_id rows and archive.subaward_funding is keyed by
         * subaward_id, so two versions of the same family can name different
         * funding Awards - the version list must not become a way to reach a
         * version whose funding the caller has no grant on. The predicate
         * goes in the same WHERE as the count, so totalElements describes
         * exactly the versions listed.
         */
        edu.bu.archive.application.authorization.SqlFragment scope =
                recordAccess.subawardScopeSql("s");

        long totalElements = repository.countVersions(subawardCode, scope);

        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(
                        safePage,
                        safeSize,
                        totalElements
                );

        int offset = safePage * safeSize;

        List<SubawardVersionSummaryResponse> content =
                repository.findVersionSummaries(
                        subawardCode,
                        scope,
                        safeSize,
                        offset
                );

        return new PageResponse<>(
                content,
                safePage,
                safeSize,
                totalElements,
                pageMetadata.totalPages(),
                pageMetadata.first(),
                pageMetadata.last()
        );
    }

    public List<SubawardAmountResponse> findAmounts(long subawardId) {
        recordAccess.requireSubaward(subawardId);
        requireSubaward(subawardId);
        return repository.findAmounts(subawardId);
    }

    public List<SubawardContactResponse> findContacts(long subawardId) {
        recordAccess.requireSubaward(subawardId);
        requireSubaward(subawardId);
        return repository.findContacts(subawardId);
    }

    public List<SubawardCustomDataResponse> findCustomData(long subawardId) {
        recordAccess.requireSubaward(subawardId);
        requireSubaward(subawardId);
        return repository.findCustomData(subawardId);
    }

    /**
     * The Subaward's funding relationships, with any Award the caller may
     * not see OMITTED ENTIRELY.
     *
     * <p>This is the endpoint where the ANY-match rule would otherwise leak.
     * Reaching the Subaward needs only ONE accessible funding Award; this
     * list carries every funding Award's number, title, status, sponsor,
     * amount and a navigable link, so returning it whole would hand the
     * caller the metadata of Awards they cannot open - through the very
     * record that inheriting access from one of them granted.
     *
     * <p>Omitted, not stubbed, following decisions D5 and D7: a placeholder
     * saying "a funding Award exists here that you may not see" still
     * discloses the relationship, and the relationship is most of what this
     * card conveys. The visible consequence is a shorter funding list, and
     * the heading does not count it - see
     * {@code resolveAssociatedAwardsSectionLabel}, which would otherwise
     * turn the shorter list into a false claim about the record.
     *
     * <p>Gated on {@code !unrestricted()}: with enforcement off, or for a
     * Central caller, the list is returned exactly as it always was - a row
     * whose award_id resolves to nothing must keep showing, since it is real
     * archived data, and only a SCOPED caller has anything to be denied.
     */
    public List<SubawardFundingResponse> findFunding(long subawardId) {
        recordAccess.requireSubaward(subawardId);
        requireSubaward(subawardId);
        List<SubawardFundingResponse> funding = repository.findFunding(subawardId);
        if (recordAccess.unrestricted()) {
            return funding;
        }
        /*
         * D7, approved 2026-10-26: display only authorized funding Awards,
         * and never reveal the existence or number of restricted ones.
         *
         * So the response is the authorized rows and NOTHING else - no count
         * of what was removed, no flag saying removal happened, no
         * placeholder row. An earlier revision returned a `restricted`
         * boolean so the UI could avoid printing a false count; that was
         * rejected, because "some funding Awards are not shown to you" is
         * itself an existence disclosure. The heading drops the count
         * instead - see resolveAssociatedAwardsSectionLabel.
         *
         * The consequence is deliberate and is the point: two Subawards
         * whose authorized funding is identical must be indistinguishable,
         * even when one has further funding Awards the caller cannot see.
         */
        return funding.stream()
                .filter(row -> row.exactLinkedAwardId() != null
                        && recordAccess.canSeeAward(row.exactLinkedAwardId()))
                .toList();
    }

    public List<SubawardAttachmentResponse> findAttachments(long subawardId) {
        recordAccess.requireSubaward(subawardId);
        requireSubaward(subawardId);
        return repository.findAttachments(subawardId);
    }

    public SubawardAttachmentDownload downloadAttachment(
            long subawardId,
            long attachmentId
    ) {
        recordAccess.requireSubaward(subawardId);
        requireSubaward(subawardId);
        if (attachmentId <= 0) {
            throw new IllegalArgumentException(
                    "Attachment ID must be positive"
            );
        }

        long owner = repository.findAttachmentSubawardId(attachmentId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Subaward attachment not found"
                ));
        if (owner != subawardId) {
            throw new NoSuchElementException(
                    "Subaward attachment not found"
            );
        }

        SubawardArchivedAttachment archived =
                repository.findArchivedAttachment(
                        subawardId,
                        attachmentId
                ).filter(row ->
                        "ARCHIVED".equals(row.archiveStatus())
                                && row.s3Bucket() != null
                                && !row.s3Bucket().isBlank()
                                && row.s3Key() != null
                                && !row.s3Key().isBlank()
                )
                .orElseThrow(() -> new NoSuchElementException(
                        "Archived attachment not found"
                ));
        SubawardAttachmentStorage.StoredObject object =
                attachmentStorage.open(archived);
        return new SubawardAttachmentDownload(
                safeFileName(
                        archived.originalFileName(),
                        attachmentId
                ),
                archived.mimeType(),
                object.contentLength(),
                object.stream()
        );
    }

    private String safeFileName(String fileName, long attachmentId) {
        String candidate = fileName == null ? "" : fileName
                .replace('\\', '/')
                .replaceAll("[\\r\\n\\p{Cntrl}]", "");
        candidate = candidate.substring(candidate.lastIndexOf('/') + 1)
                .trim();
        return candidate.isEmpty()
                ? "attachment-" + attachmentId + ".bin"
                : candidate;
    }

    public SubawardTemplateInfoResponse findTemplateInfo(long subawardId) {
        recordAccess.requireSubaward(subawardId);
        requireSubaward(subawardId);
        return repository.findTemplateInfo(subawardId)
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Subaward template info not found: "
                                        + subawardId
                        )
                );
    }

    public List<SubawardCloseoutResponse> findCloseout(long subawardId) {
        recordAccess.requireSubaward(subawardId);
        requireSubaward(subawardId);
        return repository.findCloseout(subawardId);
    }

    public List<SubawardReportResponse> findReports(long subawardId) {
        recordAccess.requireSubaward(subawardId);
        requireSubaward(subawardId);
        return repository.findReports(subawardId);
    }

    public List<SubawardNotepadResponse> findNotepad(long subawardId) {
        recordAccess.requireSubaward(subawardId);
        requireSubaward(subawardId);
        return repository.findNotepad(subawardId);
    }

    public List<SubawardNotificationResponse> findNotifications(
            long subawardId
    ) {
        recordAccess.requireSubaward(subawardId);
        requireSubaward(subawardId);
        return repository.findNotifications(subawardId);
    }

    private SubawardRowResponse requireSubaward(long subawardId) {
        if (subawardId <= 0) {
            throw new IllegalArgumentException(
                    "Subaward ID must be positive"
            );
        }

        return repository.findById(subawardId)
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Subaward not found: " + subawardId
                        )
                );
    }

    /*
     * Display fields for a set of Subawards, keyed by subaward code -
     * the identifier a semantic result carries (QA TC-041). One query
     * for the whole set; see the repository for why.
     */
    /**
     * Display fields for the Subaward semantic results on one Global Search
     * page.
     *
     * <p>Scoped in SQL, not afterwards. Global Search calls this to turn a
     * bare subaward_code into a title; a code the caller may not open must
     * produce no row, because the caller of this method treats an absent
     * summary as a row to drop. Returning it unenriched would still publish
     * the code and its link, which is the disclosure the filter exists for.
     */
    public List<SubawardSemanticSummaryRow> findActiveSummariesForCodes(
            List<String> subawardCodes
    ) {
        recordAccess.requireProvisioned();
        return repository.findActiveSummariesForCodes(
                subawardCodes, recordAccess.subawardScopeSql("s"));
    }

}
