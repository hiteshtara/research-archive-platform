package edu.bu.archive.adapter.in.web.dto.subaward;

import java.time.LocalDateTime;

/*
 * contactTypeDescription resolves CONTACT_TYPE_CODE through the shared
 * CONTACT_TYPE lookup (SubAwardContact.java's own contactType
 * reference-descriptor - the same table Award's own contacts use, not
 * a Subaward-specific type), denormalized at ETL time (see
 * oracle/subaward/export_subaward_contacts.sql).
 *
 * A contact row is linked to EITHER a Rolodex entry OR an internal BU
 * person (SubAwardContact.setRolodex()/setKcPerson() are mutually
 * exclusive), never both - fullName/organization/email/phone resolve
 * whichever is actually set, at read time, against archive.rolodex
 * (comprehensive) and archive.person (deliberately scoped only to
 * person_ids already referenced by archive.unit_administrator/
 * archive.award_unit_contact - a Subaward-linked person outside that
 * scope resolves to null here, not fabricated). rolodexId/
 * requisitionerId are preserved for audit but are secondary metadata -
 * the UI should lead with fullName, not the ID.
 */
public record SubawardContactResponse(
        Long subawardContactId,
        Long subawardId,
        String subawardCode,
        Integer sequenceNumber,
        String contactTypeCode,
        String contactTypeDescription,
        String fullName,
        String organization,
        String email,
        String phone,
        Long rolodexId,
        String requisitionerId,
        LocalDateTime sourceUpdateTimestamp,
        String sourceUpdateUser,
        Long sourceVersionNumber,
        String sourceObjectId
) {
}
