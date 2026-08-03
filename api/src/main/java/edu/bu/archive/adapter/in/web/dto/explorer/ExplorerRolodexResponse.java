package edu.bu.archive.adapter.in.web.dto.explorer;

public record ExplorerRolodexResponse(
        Long rolodexId,
        String firstName,
        String lastName,
        String organization,
        String phone,
        String email,
        String city,
        String state,
        Boolean active
) {
}
