ALTER TABLE archive.proposal_person
ADD COLUMN project_role VARCHAR(200);

ALTER TABLE archive.proposal_person
ADD COLUMN faculty_flag VARCHAR(1);

ALTER TABLE archive.proposal_person
ADD COLUMN academic_year_effort NUMERIC(8,2);

ALTER TABLE archive.proposal_person
ADD COLUMN calendar_year_effort NUMERIC(8,2);

ALTER TABLE archive.proposal_person
ADD COLUMN summer_effort NUMERIC(8,2);

ALTER TABLE archive.proposal_person
ADD COLUMN total_effort NUMERIC(8,2);

ALTER TABLE archive.proposal_person
ADD COLUMN source_update_user VARCHAR(60);

ALTER TABLE archive.proposal_person
ADD COLUMN ver_nbr INTEGER;
