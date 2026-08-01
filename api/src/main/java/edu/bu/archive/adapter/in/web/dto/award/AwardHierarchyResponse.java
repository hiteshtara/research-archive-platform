package edu.bu.archive.adapter.in.web.dto.award;

import java.util.List;

public record AwardHierarchyResponse(
        String rootAwardNumber,
        String requestedAwardNumber,
        AwardHierarchyNodeResponse root,
        List<String> selectedAwardPath
) {
}
