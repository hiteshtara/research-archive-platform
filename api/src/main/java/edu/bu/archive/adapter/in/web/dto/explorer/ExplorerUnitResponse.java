package edu.bu.archive.adapter.in.web.dto.explorer;

import java.util.List;

public record ExplorerUnitResponse(
        String unitNumber,
        String unitName,
        String parentUnitNumber,
        String parentUnitName,
        String organization,
        List<ExplorerUnitAdministratorResponse> administrators
) {
}
