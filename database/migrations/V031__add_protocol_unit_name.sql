ALTER TABLE archive.protocol_unit
    ADD COLUMN IF NOT EXISTS unit_name VARCHAR(500);
