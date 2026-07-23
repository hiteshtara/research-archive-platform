package edu.bu.archive.adapter.in.web.dto.subaward;

import java.time.LocalDateTime;

public record SubawardNotepadResponse(
        Long subawardNotepadId,
        Long subawardId,
        String subawardCode,
        Integer entryNumber,
        String noteTopic,
        String comments,
        String restrictedView,
        LocalDateTime createTimestamp,
        String createUser,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
