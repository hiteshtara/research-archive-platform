package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.subaward.SubawardNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardRowResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardSummaryResponse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubawardArchiveRepositoryTest {

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

    private SubawardRowResponse subawardRow() {
        return new SubawardRowResponse(
                101L, "DOC-101", 4, "1004", null, null, null, null,
                null, "Subaward title", null, "Active", null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, "ACTIVE", null, null, null, null, null, null, null,
                1L, "OBJECT-1", null, null, 1L, "DOCUMENT-OBJECT-1"
        );
    }
}
