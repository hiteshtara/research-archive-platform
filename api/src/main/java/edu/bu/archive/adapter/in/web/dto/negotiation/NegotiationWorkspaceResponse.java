package edu.bu.archive.adapter.in.web.dto.negotiation;

public record NegotiationWorkspaceResponse(
        Long negotiationId,
        NegotiationRowResponse current
) {
}
