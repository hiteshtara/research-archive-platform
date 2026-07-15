package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.IrbSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.domain.model.IrbProtocol;
import edu.bu.archive.domain.model.PageResult;

final class IrbWebMapper {

    private IrbWebMapper() {
    }

    static IrbSummaryResponse toResponse(IrbProtocol protocol) {
        return new IrbSummaryResponse(
                protocol.recordId(),
                protocol.studyId(),
                protocol.protocolBase(),
                protocol.protocolNumber(),
                protocol.title(),
                protocol.protocolType(),
                protocol.protocolStatus(),
                protocol.approvalDate(),
                protocol.piBuid(),
                protocol.piFullName(),
                protocol.piEmail(),
                protocol.piBuidMissing(),
                protocol.active()
        );
    }

    static PageResponse<IrbSummaryResponse> toResponse(
            PageResult<IrbProtocol> page
    ) {
        return new PageResponse<>(
                page.content()
                        .stream()
                        .map(IrbWebMapper::toResponse)
                        .toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.first(),
                page.last()
        );
    }
}
