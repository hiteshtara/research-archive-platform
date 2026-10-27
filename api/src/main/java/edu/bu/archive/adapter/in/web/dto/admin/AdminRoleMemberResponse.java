package edu.bu.archive.adapter.in.web.dto.admin;

import java.time.Instant;

import edu.bu.archive.application.authorization.admin.AdminRoleMember;

/** One ARCHIVE_ADMIN membership. Administration is not data access. */
public record AdminRoleMemberResponse(
        long adminMemberId,
        String institutionalIdentifier,
        String status,
        String grantedBy,
        Instant grantedAt,
        String reason) {

    public static AdminRoleMemberResponse from(AdminRoleMember member) {
        return new AdminRoleMemberResponse(
                member.adminMemberId(),
                member.member().value(),
                member.status().name(),
                member.grantedBy(),
                member.grantedAt(),
                member.reason());
    }
}
