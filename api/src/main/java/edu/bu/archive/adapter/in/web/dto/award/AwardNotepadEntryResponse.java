package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDateTime;

/*
 * archive.award_notepad has no sequence_number - scoped to the whole
 * award_number family, not one version (see V042's header comment).
 * Looked up by award_number, not award_id, for that reason.
 */
public record AwardNotepadEntryResponse(
        Long awardNotepadId,
        Integer entryNumber,
        String noteTopic,
        String comments,
        String restrictedView,
        LocalDateTime sourceCreateTimestamp,
        String sourceCreateUser,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser
) {
}
