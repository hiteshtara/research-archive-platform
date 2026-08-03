package edu.bu.archive.application.award;

import edu.bu.archive.adapter.in.web.dto.award.AwardCentralAdministrationContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitDetailsResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AwardContactServiceTest {

    private AwardArchiveRepository repository;
    private AwardContactService service;

    @BeforeEach
    void setUp() {
        repository = mock(AwardArchiveRepository.class);
        service = new AwardContactService(repository);
    }

    @Test
    void findUnitDetailsThrowsNotFoundForAMissingAward() {
        when(repository.findAwardNumberForId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findUnitDetails(999L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Award not found: 999");
    }

    @Test
    void findUnitDetailsThrowsNotFoundWhenNoLeadUnitIsArchived() {
        when(repository.findAwardNumberForId(985585L))
                .thenReturn(Optional.of("100012-00002"));
        when(repository.findUnitDetails(985585L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findUnitDetails(985585L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("985585");
    }

    @Test
    void findUnitDetailsReturnsTheAwardsLeadUnit() {
        AwardUnitDetailsResponse expected = new AwardUnitDetailsResponse(
                "1203250000", "CAS SPACE PHYSICS", "1200000000",
                "COLLEGE OF ARTS & SCIENCES (CAS)", "1", true
        );
        when(repository.findAwardNumberForId(985585L))
                .thenReturn(Optional.of("100012-00002"));
        when(repository.findUnitDetails(985585L))
                .thenReturn(Optional.of(expected));

        assertThat(service.findUnitDetails(985585L)).isEqualTo(expected);
    }

    @Test
    void findUnitContactsThrowsNotFoundForAMissingAward() {
        when(repository.findAwardNumberForId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findUnitContacts(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findUnitContactsReturnsTheRealArchivedContactData() {
        AwardUnitContactResponse erin = new AwardUnitContactResponse(
                "U17311007", "ERIN REYNOLDS",
                "Post-Award - Department Administrator", "1203250000", true,
                "EREYNOLD@BU.EDU", "617-358-0603"
        );
        when(repository.findAwardNumberForId(985585L))
                .thenReturn(Optional.of("100012-00002"));
        when(repository.findUnitContacts(985585L)).thenReturn(List.of(erin));

        assertThat(service.findUnitContacts(985585L)).containsExactly(erin);
    }

    @Test
    void findSponsorContactsReturnsEmptyWhenGenuinelyNone() {
        when(repository.findAwardNumberForId(985585L))
                .thenReturn(Optional.of("100012-00002"));
        when(repository.findSponsorContacts(985585L)).thenReturn(List.of());

        assertThat(service.findSponsorContacts(985585L)).isEmpty();
    }

    @Test
    void findSponsorContactsThrowsNotFoundForAMissingAward() {
        when(repository.findAwardNumberForId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findSponsorContacts(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findCentralAdministrationContactsReturnsTheProvenFixture() {
        AwardCentralAdministrationContactResponse nancy =
                new AwardCentralAdministrationContactResponse(
                        "U44984650", "NANCY SCHINDELE", "PAFO Administrator",
                        "NANCYSCH@BU.EDU", "617-358-5117"
                );
        AwardCentralAdministrationContactResponse anthony =
                new AwardCentralAdministrationContactResponse(
                        "U98756203", "ANTHONY J MOY", "OSP Administrator",
                        "TMOY@BU.EDU", "617-353-4365"
                );
        when(repository.findAwardNumberForId(985585L))
                .thenReturn(Optional.of("100012-00002"));
        when(repository.findCentralAdministrationContacts(985585L))
                .thenReturn(List.of(anthony, nancy));

        List<AwardCentralAdministrationContactResponse> result =
                service.findCentralAdministrationContacts(985585L);

        assertThat(result).containsExactly(anthony, nancy);
    }

    @Test
    void findCentralAdministrationContactsThrowsNotFoundForAMissingAward() {
        when(repository.findAwardNumberForId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findCentralAdministrationContacts(999L))
                .isInstanceOf(NoSuchElementException.class);
    }
}
