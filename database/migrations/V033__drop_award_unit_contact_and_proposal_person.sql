-- Schema cleanup: archive.award_unit_contact and archive.proposal_person
-- are no longer part of the application (Award unit-contacts and Proposal
-- people features were removed). Historical migrations V014, V015, and
-- V016 are not modified - this migration only drops what they created.
--
-- Neither table has another table's foreign key pointing to it, so no
-- CASCADE or dependency ordering beyond IF EXISTS is required. This does
-- not touch archive.award_version or archive.proposal_version.

DROP TABLE IF EXISTS archive.award_unit_contact;
DROP TABLE IF EXISTS archive.proposal_person;
