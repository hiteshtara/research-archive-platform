package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.subaward.SubawardContactResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardFundingResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardRowResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardSummaryResponse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

import static edu.bu.archive.testsupport.SubawardFixtures.subawardRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubawardArchiveRepositoryTest {

    @Test
    void unfilteredSubawardsUsePrimaryKeyOrderForFastPaging() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<SubawardSummaryResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(statement);
        when(statement.query(SubawardSummaryResponse.class)).thenReturn(query);
        SubawardSummaryResponse expected = new SubawardSummaryResponse(
                101L, "1004", 4, "DOC-101", "Title", 1L, "Active",
                "ORG-1", "ACCOUNT-1", null, null, "ACTIVE", null
        );
        when(query.list()).thenReturn(List.of(expected));

        List<SubawardSummaryResponse> result =
                new SubawardArchiveRepository(jdbc)
                        .findSubawards("", 25, 0);

        assertThat(firstSql(jdbc))
                .contains("ORDER BY subaward_id DESC")
                .containsPattern(
                        "ORDER BY subaward_id DESC\\s+LIMIT :limit"
                )
                .doesNotContain("DESCLIMIT")
                .doesNotContain("source_update_timestamp DESC")
                .doesNotContain("ILIKE");
        assertThat(result).containsExactly(expected);
        verify(statement).param("limit", 25);
        verify(statement).param("offset", 0);
    }

    @Test
    void findByIdMapsThePhysicalSubawardRow() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<SubawardRowResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);
        SubawardRowResponse expected = subawardRow();

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("subawardId", 101L)).thenReturn(statement);
        when(statement.query(SubawardRowResponse.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.of(expected));

        SubawardArchiveRepository repository =
                new SubawardArchiveRepository(jdbc);

        assertThat(repository.findById(101L)).contains(expected);
        assertThat(firstSql(jdbc))
                .contains("FROM archive.subaward")
                .contains("subaward_id = :subawardId")
                .contains("document_number")
                .contains("sequence_number")
                .contains("source_version_number")
                .contains("source_object_id");
    }

    @Test
    void findSubawardsUsesPhysicalRowsAndPagination() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<SubawardSummaryResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(statement);
        when(statement.query(SubawardSummaryResponse.class)).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        SubawardArchiveRepository repository =
                new SubawardArchiveRepository(jdbc);
        repository.findSubawards("1004", 25, 50);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.subaward")
                .contains("subaward_code")
                .contains("sequence_number")
                .contains("subaward_id")
                .doesNotContain("PARTITION BY subaward_code");
        verify(statement).param("query", "1004");
        verify(statement).param("limit", 25);
        verify(statement).param("offset", 50);
    }

    @Test
    void findNotificationsReturnsTheEmptyTableResult() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<SubawardNotificationResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("subawardId", 101L)).thenReturn(statement);
        when(statement.query(SubawardNotificationResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        SubawardArchiveRepository repository =
                new SubawardArchiveRepository(jdbc);

        assertThat(repository.findNotifications(101L)).isEmpty();
        assertThat(firstSql(jdbc))
                .contains("FROM archive.subaward_notification")
                .contains("owning_document_id_fk = :subawardId");
    }

    @Test
    void findAttachmentsExposesOnlySuccessfulArchiveAvailability() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<
                edu.bu.archive.adapter.in.web.dto.subaward
                        .SubawardAttachmentResponse
                > query = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("subawardId", 94202L)).thenReturn(statement);
        when(statement.query(
                edu.bu.archive.adapter.in.web.dto.subaward
                        .SubawardAttachmentResponse.class
        )).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new SubawardArchiveRepository(jdbc).findAttachments(94202L);

        assertThat(firstSql(jdbc))
                .contains("LEFT JOIN archive.subaward_attachment_archive")
                .contains("archived.archive_status = 'ARCHIVED'")
                .contains("archived.attachment_id IS NOT NULL AS archived");
    }

    @Test
    void findArchivedAttachmentIsScopedToAttachmentAndSubaward() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<SubawardArchivedAttachment> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("attachmentId", 500L)).thenReturn(statement);
        when(statement.param("subawardId", 94202L)).thenReturn(statement);
        when(statement.query(SubawardArchivedAttachment.class))
                .thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());

        new SubawardArchiveRepository(jdbc)
                .findArchivedAttachment(94202L, 500L);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.subaward_attachment_archive")
                .contains("attachment_id = :attachmentId")
                .contains("subaward_id = :subawardId");
    }

    @Test
    void findContactsResolvesFullNameEitherThroughRolodexOrPersonNeverBoth() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<SubawardContactResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("subawardId", 17206L)).thenReturn(statement);
        when(statement.query(SubawardContactResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new SubawardArchiveRepository(jdbc).findContacts(17206L);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.subaward_contact contact")
                .contains("LEFT JOIN archive.rolodex rolodex")
                .contains("rolodex.rolodex_id = contact.rolodex_id")
                .contains("LEFT JOIN archive.person person")
                .contains("person.person_id = contact.requisitioner_id")
                .contains("COALESCE( person.full_name,")
                .contains("rolodex.organization")
                .contains(
                        "COALESCE(person.email_address, rolodex.email_address) AS email"
                )
                .contains(
                        "COALESCE(person.phone_number, rolodex.phone_number) AS phone"
                )
                .contains("contact.contact_type_description");
    }

    /*
     * Association navigation tests - see
     * SubawardArchiveRepository.findFunding's own comment for the
     * resolution rules being locked in here. JdbcClient is mocked (this
     * repository layer is unit-tested against SQL shape + response
     * pass-through only, never a real database), so the actual
     * award_number/is_primary_current JOIN semantics are proven
     * separately, live, against real dev data (fixtures 1363/1414 for
     * the unresolved case, Award family 202505-00002 for the resolved
     * case) - not re-asserted here.
     */
    @Test
    void findFundingResolvesTheCurrentAwardVersionByAwardNumberNotByTheStaleExactLinkedId() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<SubawardFundingResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("subawardId", 17206L)).thenReturn(statement);
        when(statement.query(SubawardFundingResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of());

        new SubawardArchiveRepository(jdbc).findFunding(17206L);

        assertThat(firstSql(jdbc))
                .contains("FROM archive.subaward_funding funding")
                .contains("LEFT JOIN archive.award_version current_award")
                .contains(
                        "current_award.award_number = funding.award_number"
                )
                .contains("current_award.is_primary_current = TRUE")
                .contains("funding.award_id AS exact_linked_award_id")
                .contains(
                        "current_award.award_id AS navigable_current_award_id"
                )
                .contains(
                        "current_award.award_id IS NOT NULL AS archived"
                )
                .contains("current_award.sponsor_name AS award_sponsor")
                .contains("LEFT JOIN LATERAL")
                .contains("ai.award_id = current_award.award_id")
                .contains("amt.obligated_total_amount AS award_amount");
    }

    @Test
    void findFundingPreservesEveryRealFundingSourceRowIncludingBothResolvedAndUnresolvedLinks() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<SubawardFundingResponse> query =
                mock(JdbcClient.MappedQuerySpec.class);

        // Row 1: exactLinkedAwardId points at a stale (non-current)
        // Award version (834149, sequence 1 of 202505-00002), but the
        // family's real current version (2036323) was resolved and
        // archived - clickable.
        SubawardFundingResponse resolved = new SubawardFundingResponse(
                501L, 17206L, "1363", 8,
                834149L, "202505-00002",
                "Neuroimaging Genetics of PTSD", "03. Pending",
                "National Institutes of Health", new java.math.BigDecimal("50000.00"),
                2036323L, true,
                null, null, null, null
        );
        // Row 2: linked, but the family's current version has not been
        // archived at all - visible, honestly non-clickable, never
        // hidden.
        SubawardFundingResponse unresolved = new SubawardFundingResponse(
                777L, 17206L, "1363", 8,
                9999999L, "203161-00002",
                null, null,
                null, null,
                null, false,
                null, null, null, null
        );

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("subawardId", 17206L)).thenReturn(statement);
        when(statement.query(SubawardFundingResponse.class))
                .thenReturn(query);
        when(query.list()).thenReturn(List.of(resolved, unresolved));

        List<SubawardFundingResponse> result =
                new SubawardArchiveRepository(jdbc).findFunding(17206L);

        assertThat(result).containsExactly(resolved, unresolved);
        assertThat(result.get(0).exactLinkedAwardId()).isEqualTo(834149L);
        assertThat(result.get(0).navigableCurrentAwardId())
                .isEqualTo(2036323L);
        assertThat(result.get(0).archived()).isTrue();
        assertThat(result.get(1).navigableCurrentAwardId()).isNull();
        assertThat(result.get(1).archived()).isFalse();
    }

    private String firstSql(JdbcClient jdbc) {
        return org.mockito.Mockito
                .mockingDetails(jdbc)
                .getInvocations()
                .stream()
                .filter(invocation ->
                        invocation.getMethod().getName().equals("sql")
                )
                .map(invocation -> (String) invocation.getArgument(0))
                .findFirst()
                .orElseThrow()
                .replaceAll("\\s+", " ");
    }

}
