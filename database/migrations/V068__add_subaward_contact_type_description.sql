-- CONTACT_TYPE_CODE resolves through the shared CONTACT_TYPE table
-- (org.kuali.kra.award.home.ContactType, table CONTACT_TYPE) - proven
-- from SubAwardContact.java's own contactType reference-descriptor in
-- repository-subAward.xml, the SAME lookup Award's own contacts use,
-- not a Subaward-specific type table. Denormalized here exactly like
-- subaward_funding.award_number, rather than a new reference table,
-- since it is a single simple code->description lookup.
ALTER TABLE archive.subaward_contact
    ADD COLUMN IF NOT EXISTS contact_type_description VARCHAR(500);
