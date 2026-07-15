package edu.bu.archive.adapter.in.web.dto.workspace;

import java.time.LocalDate;

public record IrbTimelineResponse(
        LocalDate date,
        String type,
        Integer sequence
) {
}
