package edu.bu.archive.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAttachmentResponse;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * Golden-shape contract test for NegotiationAttachmentResponse - a guard
 * against an accidental breaking change or an accidental storage-internal
 * leak (s3Bucket/s3Key/checksum/sha256), mirroring AwardV1ContractTest's
 * own attachmentShapeIsStableAndNeverExposesS3BucketOrKey test. See
 * docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md for why
 * oracleAttachmentId/oracleFileId/description were added and why
 * checksum was removed in the same change.
 */
class NegotiationAttachmentContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void attachmentShapeIsStableAndNeverExposesStorageInternals()
            throws Exception {
        NegotiationAttachmentResponse attachment =
                new NegotiationAttachmentResponse(
                        420L, 10134L, "kotton-proteostasis.pdf",
                        "application/pdf", 1024L, "ARCHIVED",
                        LocalDateTime.of(2015, 7, 24, 0, 0), "jlrevvy",
                        true, "N",
                        101L, "24828", "Kotton Proteostasis"
                );

        assertFieldNames(attachment, Set.of(
                "attachmentId", "activityId", "fileName", "contentType",
                "fileSize", "archiveStatus", "sourceUpdateTimestamp",
                "sourceUpdateUser", "downloadable", "restrictedFlag",
                "oracleAttachmentId", "oracleFileId", "description"
        ));

        String json = objectMapper.writeValueAsString(attachment);
        assertThat(json).doesNotContainIgnoringCase("s3Bucket");
        assertThat(json).doesNotContainIgnoringCase("s3Key");
        assertThat(json).doesNotContainIgnoringCase("checksum");
        assertThat(json).doesNotContainIgnoringCase("sha256");
        assertThat(json).doesNotContainIgnoringCase("storagePath");
        assertThat(json).doesNotContainIgnoringCase("blob");

        JsonNode node = objectMapper.valueToTree(attachment);
        assertThat(node.get("description").asText())
                .isEqualTo("Kotton Proteostasis");
        assertThat(node.get("oracleAttachmentId").asLong())
                .isEqualTo(101L);
        assertThat(node.get("oracleFileId").asText())
                .isEqualTo("24828");
        assertThat(node.get("activityId").asLong()).isEqualTo(10134L);
    }

    @Test
    void restrictedFlagIsInformationalAndNeverAffectsDownloadable()
            throws Exception {
        NegotiationAttachmentResponse restrictedButArchived =
                new NegotiationAttachmentResponse(
                        1L, 2L, "file.pdf", "application/pdf", 10L,
                        "ARCHIVED", null, null, true, "Y",
                        3L, "4", "Marked restricted but archived"
                );
        NegotiationAttachmentResponse notRestrictedButMissing =
                new NegotiationAttachmentResponse(
                        5L, 6L, "file2.pdf", "application/pdf", 10L,
                        "MISSING", null, null, false, "N",
                        7L, "8", "Not restricted but no BLOB"
                );

        // downloadable tracks archive_status/S3 presence only - never
        // restrictedFlag. A "Y" attachment can be downloadable; an "N"
        // attachment can be unavailable.
        assertThat(restrictedButArchived.downloadable()).isTrue();
        assertThat(notRestrictedButMissing.downloadable()).isFalse();

        String restrictedJson =
                objectMapper.writeValueAsString(restrictedButArchived);
        String notRestrictedJson =
                objectMapper.writeValueAsString(notRestrictedButMissing);
        assertThat(restrictedJson).contains("\"restrictedFlag\":\"Y\"");
        assertThat(notRestrictedJson).contains("\"restrictedFlag\":\"N\"");
    }

    private void assertFieldNames(Object value, Set<String> expected)
            throws Exception {
        JsonNode node = objectMapper.valueToTree(value);
        Set<String> actual = new HashSet<>();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            actual.add(names.next());
        }
        assertThat(actual).isEqualTo(expected);
    }
}
