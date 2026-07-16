package edu.bu.archive.adapter.in.web.dto;

public record GlobalSearchItemResponse(
        Long recordId,
        Long protocolId,
        String module,
        String identifier,
        String secondaryIdentifier,
        String title,
        String status,
        String personName,
        String recordType
) {
}
