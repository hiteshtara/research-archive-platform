package edu.bu.archive.application.award;

import edu.bu.archive.adapter.in.web.dto.award.AwardCentralAdministrationContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitDetailsResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/*
 * Assembles the Award "People and Units" page's four non-Key-Personnel
 * sections - Unit Details, Unit Contacts, Sponsor Contacts, Central
 * Administration Contacts - all built from the shared reference model
 * (archive.unit/unit_administrator/unit_administrator_type/rolodex/
 * person) rather than Award-owned duplicates. A deliberately separate
 * class from AwardArchiveService (which keeps Key Personnel on the
 * existing /people endpoint) so this feature's queries/tests stay
 * scoped to exactly what they touch. See
 * docs/architecture/AWARD_CONTACTS_DESIGN.md for the proven Kuali
 * derivation rules this reproduces.
 */
@Service
public class AwardContactService {

    private final AwardArchiveRepository repository;

    public AwardContactService(AwardArchiveRepository repository) {
        this.repository = repository;
    }

    public AwardUnitDetailsResponse findUnitDetails(long awardId) {
        requireAwardExists(awardId);
        return repository.findUnitDetails(awardId)
                .orElseThrow(() -> new NoSuchElementException(
                        "No lead unit archived for award_id: " + awardId
                ));
    }

    public List<AwardUnitContactResponse> findUnitContacts(long awardId) {
        requireAwardExists(awardId);
        return repository.findUnitContacts(awardId);
    }

    public List<AwardSponsorContactResponse> findSponsorContacts(
            long awardId
    ) {
        requireAwardExists(awardId);
        return repository.findSponsorContacts(awardId);
    }

    public List<AwardCentralAdministrationContactResponse>
            findCentralAdministrationContacts(long awardId) {
        requireAwardExists(awardId);
        return repository.findCentralAdministrationContacts(awardId);
    }

    private void requireAwardExists(long awardId) {
        repository.findAwardNumberForId(awardId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Award not found: " + awardId
                ));
    }
}
