package edu.bu.archive.application.award;

import edu.bu.archive.adapter.in.web.dto.award.AwardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCentralAdministrationContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardDocumentNumberMatchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitDetailsResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerAwardContactsResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerAwardResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerPersonResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerProposalDiscoveryResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitAdministratorResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitRow;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import edu.bu.archive.adapter.out.persistence.AwardAttachmentStorage;
import edu.bu.archive.adapter.in.web.dto.PageResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExplorerServiceTest {

    private AwardArchiveRepository repository;
    private AwardArchiveService awardArchiveService;
    private AwardContactService contactService;
    private ExplorerService service;

    @BeforeEach
    void setUp() {
        repository = mock(AwardArchiveRepository.class);
        awardArchiveService = new AwardArchiveService(
                repository, mock(AwardAttachmentStorage.class)
        );
        contactService = mock(AwardContactService.class);
        service = new ExplorerService(
                repository, awardArchiveService, contactService
        );
    }

    @Test
    void findAwardThrowsNotFoundForAMissingAwardNumber() {
        when(repository.findExplorerAwardByNumber("NO-SUCH"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findAward("NO-SUCH"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findAwardReturnsTheCurrentVersion() {
        ExplorerAwardResponse expected = new ExplorerAwardResponse(
                985585L, "100012-00002", 7, "Title", "Closed",
                "JOHN T CLARKE", "300940", null, "1203250000",
                "CAS SPACE PHYSICS", true
        );
        when(repository.findExplorerAwardByNumber("100012-00002"))
                .thenReturn(Optional.of(expected));

        assertThat(service.findAward("100012-00002")).isEqualTo(expected);
    }

    @Test
    void findAwardVersionThrowsNotFoundForAMissingAwardId() {
        when(repository.findExplorerAwardVersionById(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findAwardVersion(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findWorkflowThrowsNotFoundWhenNoVersionMatches() {
        when(repository.findExactWorkflowDocumentMatch("000000"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findWorkflow("000000"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findWorkflowReturnsTheMatch() {
        AwardDocumentNumberMatchResponse match =
                new AwardDocumentNumberMatchResponse(
                        1135067L, "100567-00001", 6, "328797", "Award",
                        "Title", "Closed"
                );
        when(repository.findExactWorkflowDocumentMatch("328797"))
                .thenReturn(Optional.of(match));

        assertThat(service.findWorkflow("328797")).isEqualTo(match);
    }

    @Test
    void findUnitThrowsNotFoundForAMissingUnitNumber() {
        when(repository.findUnitByNumber("999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findUnit("999999"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findUnitCombinesTheUnitRowAndItsAdministrators() {
        ExplorerUnitRow row = new ExplorerUnitRow(
                "1203250000", "CAS SPACE PHYSICS", "1200000000",
                "COLLEGE OF ARTS & SCIENCES (CAS)", "1"
        );
        ExplorerUnitAdministratorResponse anthony =
                new ExplorerUnitAdministratorResponse(
                        "U98756203", "ANTHONY J MOY", "3",
                        "OSP Administrator", "C", "TMOY@BU.EDU",
                        "617-353-4365"
                );
        when(repository.findUnitByNumber("1203250000"))
                .thenReturn(Optional.of(row));
        when(repository.findUnitAdministratorsByUnitNumber("1203250000"))
                .thenReturn(List.of(anthony));

        ExplorerUnitResponse result = service.findUnit("1203250000");

        assertThat(result.unitNumber()).isEqualTo("1203250000");
        assertThat(result.parentUnitName())
                .isEqualTo("COLLEGE OF ARTS & SCIENCES (CAS)");
        assertThat(result.administrators()).containsExactly(anthony);
    }

    @Test
    void findAwardContactsAggregatesAllFourSections() {
        ExplorerAwardResponse award = new ExplorerAwardResponse(
                985585L, "100012-00002", 7, "Title", "Closed",
                "JOHN T CLARKE", "300940", null, "1203250000",
                "CAS SPACE PHYSICS", true
        );
        AwardUnitDetailsResponse unitDetails = new AwardUnitDetailsResponse(
                "1203250000", "CAS SPACE PHYSICS", "1200000000",
                "COLLEGE OF ARTS & SCIENCES (CAS)", "1", true
        );
        AwardUnitContactResponse unitContact = new AwardUnitContactResponse(
                "U17311007", "ERIN REYNOLDS",
                "Post-Award - Department Administrator", "1203250000", true,
                "EREYNOLD@BU.EDU", "617-358-0603"
        );
        AwardCentralAdministrationContactResponse centralAdmin =
                new AwardCentralAdministrationContactResponse(
                        "U98756203", "ANTHONY J MOY", "OSP Administrator",
                        "TMOY@BU.EDU", "617-353-4365"
                );

        when(repository.findExplorerAwardVersionById(985585L))
                .thenReturn(Optional.of(award));
        when(repository.findAwardNumberForId(985585L))
                .thenReturn(Optional.of("100012-00002"));
        when(repository.findPersonRows(985585L)).thenReturn(List.of());
        when(repository.findPersonUnitRows(985585L)).thenReturn(List.of());
        when(repository.findPersonCreditSplitRows(985585L))
                .thenReturn(List.of());
        when(repository.findPersonUnitCreditSplitRows(985585L))
                .thenReturn(List.of());
        when(contactService.findUnitDetails(985585L)).thenReturn(unitDetails);
        when(contactService.findUnitContacts(985585L))
                .thenReturn(List.of(unitContact));
        when(contactService.findSponsorContacts(985585L)).thenReturn(List.of());
        when(contactService.findCentralAdministrationContacts(985585L))
                .thenReturn(List.of(centralAdmin));

        ExplorerAwardContactsResponse result =
                service.findAwardContacts(985585L);

        assertThat(result.award()).isEqualTo(award);
        assertThat(result.unitDetails()).isEqualTo(unitDetails);
        assertThat(result.unitContacts()).containsExactly(unitContact);
        assertThat(result.sponsorContacts()).isEmpty();
        assertThat(result.centralAdministrationContacts())
                .containsExactly(centralAdmin);
    }

    @Test
    void findPersonThrowsNotFoundWhenNotArchived() {
        when(repository.findExplorerPersonById("U00000000"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findPerson("U00000000"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findPersonReturnsTheArchivedPerson() {
        ExplorerPersonResponse person = new ExplorerPersonResponse(
                "U44984650", "NANCY", null, "SCHINDELE", "NANCY SCHINDELE",
                "NANCYSCH@BU.EDU", "617-358-5117"
        );
        when(repository.findExplorerPersonById("U44984650"))
                .thenReturn(Optional.of(person));

        assertThat(service.findPerson("U44984650")).isEqualTo(person);
    }

    @Test
    void findRolodexThrowsNotFoundForAMissingRolodexId() {
        when(repository.findExplorerRolodexById(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findRolodex(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findSponsorsByCodeQueriesAwardsNotAwardSponsorContacts() {
        ExplorerAwardResponse award = new ExplorerAwardResponse(
                985585L, "100012-00002", 7, "Title", "Closed",
                "JOHN T CLARKE", "300940", null, "1203250000",
                "CAS SPACE PHYSICS", true
        );
        when(repository.findAwardsBySponsorCode("NIH"))
                .thenReturn(List.of(award));

        List<ExplorerAwardResponse> result =
                service.findSponsorsByCode("NIH");

        assertThat(result).containsExactly(award);
    }

    @Test
    void findAttachmentsReturnsTheContentOfTheFirstCappedPage() {
        AwardAttachmentResponse attachment = new AwardAttachmentResponse(
                1L, "100068-00001", 1, "agreement.pdf", "application/pdf",
                null, null, null, 1024L, "UPLOADED", true, null
        );
        when(repository.findAwardNumberForId(1833767L))
                .thenReturn(Optional.of("100068-00001"));
        when(repository.findAttachments(1833767L, 50, 0))
                .thenReturn(List.of(attachment));
        when(repository.countAttachments(1833767L)).thenReturn(1L);

        List<AwardAttachmentResponse> result =
                service.findAttachments(1833767L);

        assertThat(result).containsExactly(attachment);
    }

    @Test
    void findProposalDiscoveryClampsPagingAndDelegatesEveryFilter() {
        ExplorerProposalDiscoveryResponse row = new ExplorerProposalDiscoveryResponse(
                1238613L, "01157400", "Title", "125761", 3,
                "200268-00001", 605555L, "Award Title",
                new BigDecimal("1500000.00"), new BigDecimal("1200000.00"),
                148155L
        );
        when(repository.findProposalDiscoveryRows(
                true, true, new BigDecimal("1000000"),
                "NIH", "1203250000", "Funded", 50, 0
        )).thenReturn(List.of(row));

        List<ExplorerProposalDiscoveryResponse> result = service.findProposalDiscovery(
                true, true, new BigDecimal("1000000"),
                "NIH", "1203250000", "Funded", 0, 50
        );

        assertThat(result).containsExactly(row);
    }

    @Test
    void findProposalDiscoveryClampsAnOversizedPageRequest() {
        when(repository.findProposalDiscoveryRows(
                null, null, null, null, null, null, 100, 0
        )).thenReturn(List.of());

        service.findProposalDiscovery(
                null, null, null, null, null, null, 0, 5000
        );

        org.mockito.Mockito.verify(repository).findProposalDiscoveryRows(
                null, null, null, null, null, null, 100, 0
        );
    }
}
