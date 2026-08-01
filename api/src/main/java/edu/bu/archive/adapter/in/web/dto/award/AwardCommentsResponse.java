package edu.bu.archive.adapter.in.web.dto.award;

import java.util.List;

public record AwardCommentsResponse(
        List<AwardCommentResponse> comments,
        List<AwardNotepadEntryResponse> notepadEntries
) {
}
