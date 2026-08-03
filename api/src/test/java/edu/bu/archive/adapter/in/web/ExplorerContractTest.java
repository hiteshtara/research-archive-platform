package edu.bu.archive.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import edu.bu.archive.adapter.in.web.dto.award.AwardCentralAdministrationContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitContactResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitDetailsResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerAwardContactsResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerAwardResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerPersonResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerRolodexResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitAdministratorResponse;
import edu.bu.archive.adapter.in.web.dto.explorer.ExplorerUnitResponse;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * Golden-shape contract tests for the six Archive Explorer response DTOs -
 * mirrors AwardV1ContractTest's approach (guard against an accidental
 * field rename/removal/re-typing since Java records don't otherwise
 * surface their JSON shape). Also asserts the Explorer's specific
 * "never expose secrets/internal DB IDs/sensitive metadata" requirement
 * for the two shared reference-entity views (Person/Rolodex).
 */
class ExplorerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void awardShapeIsStable() throws Exception {
        ExplorerAwardResponse award = new ExplorerAwardResponse(
                985585L, "100012-00002", 7, "Title", "Closed",
                "JOHN T CLARKE", "300940", null, "1203250000",
                "CAS SPACE PHYSICS", true
        );

        assertFieldNames(award, Set.of(
                "awardId", "awardNumber", "sequenceNumber", "title",
                "status", "principalInvestigator", "workflowDocumentNumber",
                "modificationNumber", "leadUnitNumber", "leadUnitName",
                "primaryCurrent"
        ));
    }

    @Test
    void unitShapeIsStableAndNestsAllAdministratorsNotOnlyGroupC()
            throws Exception {
        ExplorerUnitAdministratorResponse anthony =
                new ExplorerUnitAdministratorResponse(
                        "U98756203", "ANTHONY J MOY", "3",
                        "OSP Administrator", "C", "TMOY@BU.EDU",
                        "617-353-4365"
                );
        ExplorerUnitAdministratorResponse nonGroupC =
                new ExplorerUnitAdministratorResponse(
                        "U11111111", "SOME OTHER ADMIN", "9",
                        "Department Administrator", "D",
                        "OTHER@BU.EDU", "617-000-0000"
                );
        ExplorerUnitResponse unit = new ExplorerUnitResponse(
                "1203250000", "CAS SPACE PHYSICS", "1200000000",
                "COLLEGE OF ARTS & SCIENCES (CAS)", "1",
                List.of(anthony, nonGroupC)
        );

        assertFieldNames(unit, Set.of(
                "unitNumber", "unitName", "parentUnitNumber",
                "parentUnitName", "organization", "administrators"
        ));

        JsonNode administrators = objectMapper.valueToTree(unit)
                .get("administrators");
        assertThat(administrators).hasSize(2);
        assertFieldNames(administrators.get(0), Set.of(
                "personId", "fullName", "administratorTypeCode",
                "administratorTypeDescription", "defaultGroupFlag",
                "email", "phone"
        ));
        // preserved for debugging, not filtered to group 'C' at this layer
        assertThat(administrators.get(1).get("defaultGroupFlag").asText())
                .isEqualTo("D");
    }

    @Test
    void awardContactsShapeIsStableAndAggregatesAllFourSections()
            throws Exception {
        ExplorerAwardResponse award = new ExplorerAwardResponse(
                985585L, "100012-00002", 7, "Title", "Closed",
                "JOHN T CLARKE", "300940", null, "1203250000",
                "CAS SPACE PHYSICS", true
        );
        AwardUnitDetailsResponse unitDetails = new AwardUnitDetailsResponse(
                "1203250000", "CAS SPACE PHYSICS", "1200000000",
                "COLLEGE OF ARTS & SCIENCES (CAS)", "1", true
        );
        ExplorerAwardContactsResponse aggregate =
                new ExplorerAwardContactsResponse(
                        award, List.of(), unitDetails,
                        List.<AwardUnitContactResponse>of(),
                        List.<AwardSponsorContactResponse>of(),
                        List.<AwardCentralAdministrationContactResponse>of()
                );

        assertFieldNames(aggregate, Set.of(
                "award", "keyPersonnel", "unitDetails", "unitContacts",
                "sponsorContacts", "centralAdministrationContacts"
        ));
    }

    @Test
    void personShapeIsStableAndNeverExposesCredentialsOrInternalIds()
            throws Exception {
        ExplorerPersonResponse person = new ExplorerPersonResponse(
                "U44984650", "NANCY", null, "SCHINDELE", "NANCY SCHINDELE",
                "NANCYSCH@BU.EDU", "617-358-5117"
        );

        assertFieldNames(person, Set.of(
                "personId", "firstName", "middleName", "lastName",
                "fullName", "email", "phone"
        ));

        String json = objectMapper.writeValueAsString(person);
        assertThat(json).doesNotContainIgnoringCase("password");
        assertThat(json).doesNotContainIgnoringCase("principalId");
        assertThat(json).doesNotContainIgnoringCase("krimEntityId");
    }

    @Test
    void rolodexShapeIsStableAndNeverExposesInternalMetadata()
            throws Exception {
        ExplorerRolodexResponse rolodex = new ExplorerRolodexResponse(
                501L, "Jane", "Smith", "NIH", "301-555-0100",
                "jane.smith@nih.gov", "Bethesda", "MD", true
        );

        assertFieldNames(rolodex, Set.of(
                "rolodexId", "firstName", "lastName", "organization",
                "phone", "email", "city", "state", "active"
        ));

        String json = objectMapper.writeValueAsString(rolodex);
        assertThat(json).doesNotContainIgnoringCase("sponsorCode");
        assertThat(json).doesNotContainIgnoringCase("password");
    }

    private void assertFieldNames(Object value, Set<String> expected)
            throws Exception {
        assertFieldNames(objectMapper.valueToTree(value), expected);
    }

    private void assertFieldNames(JsonNode node, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            actual.add(names.next());
        }
        assertThat(actual).isEqualTo(expected);
    }
}
