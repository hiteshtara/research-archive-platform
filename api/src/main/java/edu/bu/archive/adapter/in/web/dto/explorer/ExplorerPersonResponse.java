package edu.bu.archive.adapter.in.web.dto.explorer;

public record ExplorerPersonResponse(
        String personId,
        String firstName,
        String middleName,
        String lastName,
        String fullName,
        String email,
        String phone
) {
}
