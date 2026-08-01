-- Read-path indexes for the Award Search/Hierarchy/Summary/Versions API
-- (docs/architecture/AWARD_SEARCH_API_DESIGN.md). Purely additive - no
-- table/column changes, no data changes. None of these are required by
-- the ETL loader; they exist solely to make the new API's queries scale.
--
-- archive.award_hierarchy has no index on root_award_number today (only
-- on award_number and parent_award_number - see V049) - the hierarchy
-- endpoint's primary lookup, WHERE root_award_number = :root, would
-- otherwise be a full table scan.
CREATE INDEX IF NOT EXISTS ix_award_hierarchy_root
    ON archive.award_hierarchy (root_award_number);

-- pg_trgm: the Award search endpoint's "partial"/"wildcard" matching
-- (ILIKE '%...%', with or without the API's own '*' wildcard syntax
-- translated to '%') cannot be served efficiently by a plain B-tree
-- index - a substring match has no fixed prefix for B-tree to seek on.
-- A trigram GIN index is what actually makes ILIKE '%...%' use an index
-- scan instead of a sequential scan. This is a standard, widely-used
-- extension (on AWS RDS's default supported-extensions list for
-- PostgreSQL) - assumed available here; if the real RDS parameter group
-- restricts it, that would need to be resolved before this migration
-- runs there (see Open Questions in the design doc).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Title: also requested explicitly as lower(title) for exact/prefix
-- lookups (kept as a plain functional B-tree index, not a trigram
-- index - lower(title) only helps front-anchored/exact matches, not
-- substring search, which is what the trigram index below is for).
CREATE INDEX IF NOT EXISTS ix_award_version_title_lower
    ON archive.award_version (lower(title));

CREATE INDEX IF NOT EXISTS ix_award_version_title_trgm
    ON archive.award_version USING gin (title gin_trgm_ops);

-- Sponsor: sponsor_code already has a plain B-tree index (ix_award_version_sponsor,
-- V011) - sponsor_name did not, and needs trigram support for substring
-- search the same way title does.
CREATE INDEX IF NOT EXISTS ix_award_version_sponsor_name_trgm
    ON archive.award_version USING gin (sponsor_name gin_trgm_ops);

-- Lead unit: lead_unit_number (typically looked up as an exact/prefix
-- code, not a substring) gets a plain B-tree index; lead_unit_name
-- (a free-text unit name) gets trigram support.
CREATE INDEX IF NOT EXISTS ix_award_version_lead_unit_number
    ON archive.award_version (lead_unit_number);

CREATE INDEX IF NOT EXISTS ix_award_version_lead_unit_name_trgm
    ON archive.award_version USING gin (lead_unit_name gin_trgm_ops);

-- Document number: mapped to archive.award_version.modification_number
-- (the real, already-archived per-version document identifier - see
-- the design doc's "FAIN and document number" section for why this,
-- not a fabricated column, is what "document number" means here).
CREATE INDEX IF NOT EXISTS ix_award_version_modification_number_trgm
    ON archive.award_version USING gin (modification_number gin_trgm_ops);

-- Person/PI name: archive.award_person.full_name already has a plain
-- B-tree index (ix_award_person_name, V011) - adds trigram support for
-- substring search, the same treatment as title/sponsor_name/lead_unit_name.
CREATE INDEX IF NOT EXISTS ix_award_person_full_name_trgm
    ON archive.award_person USING gin (full_name gin_trgm_ops);
