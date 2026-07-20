package edu.bu.archive.adapter.in.web.dto.award;

public record AwardWorkspaceResponse(
        String awardNumber,
        AwardRowResponse current
) {
}
