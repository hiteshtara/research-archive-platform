package edu.bu.archive.adapter.in.web.dto.award;

import java.util.List;

public record AwardCommentsResponse(
        List<AwardCommentCategoryResponse> commentCategories,
        List<AwardNotepadEntryResponse> notepadEntries
) {
}
