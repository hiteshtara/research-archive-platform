-- Forward-only removal of the Protocol Archive schema (V021-V029, V031).
-- Protocol Archive is a distinct module from legacy IRB (archive.irb_*
-- tables/views) and is being removed from the application; IRB is untouched
-- by this migration. Historical migrations V021-V029 and V031 are not
-- modified or rewritten - this migration only drops what they created.
--
-- Drop order: dependent views first, then tables in FK-dependency order
-- (deepest child first, root table last). Indexes and the location check
-- constraint are defined on these tables and are dropped automatically with
-- them; there are no Protocol-specific functions to drop.

DROP VIEW IF EXISTS archive.v_protocol_family;
DROP VIEW IF EXISTS archive.v_protocol_latest;

DROP TABLE IF EXISTS archive.protocol_unit;
DROP TABLE IF EXISTS archive.protocol_person;
DROP TABLE IF EXISTS archive.protocol_funding;
DROP TABLE IF EXISTS archive.protocol_research_area;
DROP TABLE IF EXISTS archive.protocol_location;
DROP TABLE IF EXISTS archive.protocol_submission;
DROP TABLE IF EXISTS archive.protocol_action;
DROP TABLE IF EXISTS archive.protocol_amend_renewal;
DROP TABLE IF EXISTS archive.protocol_version;
