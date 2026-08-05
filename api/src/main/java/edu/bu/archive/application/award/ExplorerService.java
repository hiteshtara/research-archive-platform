package edu.bu.archive.application.award;

import edu.bu.archive.adapter.in.web.dto.PaginationSupport;
import edu.bu.archive.adapter.in.web.dto.award.AwardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardDocumentNumberMatchResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerAwardContactsResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerAwardResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerPersonResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerProposalDiscoveryResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerRolodexResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitAdministratorResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitRow;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

/*
 * Backs the Archive Explorer's Phase 2 web endpoints
 * (ExplorerController, /api/v1/explorer/...) - runs in-process against
 * the SAME repositories/PostgreSQL connection the rest of the API uses,
 * never spawning a Fargate task per request (that remains Phase 1's own
 * ECS CLI - see docs/ARCHIVE_EXPLORER.md). Deliberately reuses
 * AwardArchiveService/AwardContactService's already-proven methods
 * wherever an equivalent already exists (Key Personnel, Unit Details,
 * Unit/Sponsor/Central Administration Contacts, attachments, exact
 * workflow-document match) rather than duplicating their SQL - only
 * adds new repository methods for lookups that don't yet exist
 * standalone (Unit/Person/Rolodex by their own identifier, not
 * Award-scoped).
 */
@Service
public class ExplorerService {

    private static final int ATTACHMENT_PAGE_SIZE = 50;

    private final AwardArchiveRepository repository;
    private final AwardArchiveService awardArchiveService;
    private final AwardContactService contactService;

    public ExplorerService(
            AwardArchiveRepository repository,
            AwardArchiveService awardArchiveService,
            AwardContactService contactService
    ) {
        this.repository = repository;
        this.awardArchiveService = awardArchiveService;
        this.contactService = contactService;
    }

    public ExplorerAwardResponse findAward(String awardNumber) {
        return repository.findExplorerAwardByNumber(awardNumber)
                .orElseThrow(() -> new NoSuchElementException(
                        "No current version archived for award_number: "
                                + awardNumber
                ));
    }

    public ExplorerAwardResponse findAwardVersion(long awardId) {
        return repository.findExplorerAwardVersionById(awardId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Award not found: " + awardId
                ));
    }

    public AwardDocumentNumberMatchResponse findWorkflow(
            String documentNumber
    ) {
        return repository.findExactWorkflowDocumentMatch(documentNumber)
                .orElseThrow(() -> new NoSuchElementException(
                        "No archived version has workflow document number: "
                                + documentNumber
                ));
    }

    public ExplorerUnitResponse findUnit(String unitNumber) {
        ExplorerUnitRow row = repository.findUnitByNumber(unitNumber)
                .orElseThrow(() -> new NoSuchElementException(
                        "Unit not found: " + unitNumber
                ));
        return new ExplorerUnitResponse(
                row.unitNumber(),
                row.unitName(),
                row.parentUnitNumber(),
                row.parentUnitName(),
                row.organization(),
                repository.findUnitAdministratorsByUnitNumber(unitNumber)
        );
    }

    public List<ExplorerUnitAdministratorResponse> findUnitAdministrators(
            String unitNumber
    ) {
        return repository.findUnitAdministratorsByUnitNumber(unitNumber);
    }

    public ExplorerAwardContactsResponse findAwardContacts(long awardId) {
        ExplorerAwardResponse award = findAwardVersion(awardId);
        return new ExplorerAwardContactsResponse(
                award,
                awardArchiveService.findPeople(awardId),
                contactService.findUnitDetails(awardId),
                contactService.findUnitContacts(awardId),
                contactService.findSponsorContacts(awardId),
                contactService.findCentralAdministrationContacts(awardId)
        );
    }

    public ExplorerPersonResponse findPerson(String personId) {
        return repository.findExplorerPersonById(personId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Person not archived: " + personId
                ));
    }

    public ExplorerRolodexResponse findRolodex(long rolodexId) {
        return repository.findExplorerRolodexById(rolodexId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Rolodex entry not found: " + rolodexId
                ));
    }

    /*
     * "Sponsors" here means current-version Awards carrying a given
     * SPONSOR_CODE - a different, standalone concept from an Award's
     * own Sponsor Contacts (already covered by findAwardContacts'
     * sponsorContacts field / the public /sponsor-contacts endpoint).
     * See AwardArchiveRepository.findAwardsBySponsorCode for why this
     * is Award-backed rather than Rolodex-backed.
     */
    public List<ExplorerAwardResponse> findSponsorsByCode(
            String sponsorCode
    ) {
        return repository.findAwardsBySponsorCode(sponsorCode);
    }

    public List<AwardAttachmentResponse> findAttachments(long awardId) {
        return awardArchiveService
                .findAttachments(awardId, 0, ATTACHMENT_PAGE_SIZE)
                .content();
    }

    public List<ExplorerProposalDiscoveryResponse> findProposalDiscovery(
            Boolean hasAttachments,
            Boolean hasFundedAward,
            BigDecimal minimumAwardAmount,
            String sponsorCode,
            String leadUnitNumber,
            String proposalStatus,
            int page,
            int size
    ) {
        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        return repository.findProposalDiscoveryRows(
                hasAttachments,
                hasFundedAward,
                minimumAwardAmount,
                sponsorCode,
                leadUnitNumber,
                proposalStatus,
                safeSize,
                safePage * safeSize
        );
    }
}
