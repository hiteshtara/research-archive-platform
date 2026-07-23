ALTER TABLE archive.protocol_person
    ADD COLUMN IF NOT EXISTS source_protocol_id BIGINT;

/*
 * V022 initially stored Oracle PROTOCOL_PERSONS.PROTOCOL_ID in protocol_id.
 * Preserve that source value before the repeatable Personnel reload resolves
 * protocol_id from PROTOCOL_NUMBER + SEQUENCE_NUMBER.
 */
UPDATE archive.protocol_person
SET source_protocol_id = protocol_id
WHERE source_protocol_id IS NULL;

ALTER TABLE archive.protocol_person
    ALTER COLUMN source_protocol_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_protocol_person_source_protocol
    ON archive.protocol_person (source_protocol_id);

COMMENT ON COLUMN archive.protocol_person.protocol_id IS
    'Resolved archive parent from protocol_number + sequence_number';

COMMENT ON COLUMN archive.protocol_person.source_protocol_id IS
    'Original KCOEUS.PROTOCOL_PERSONS.PROTOCOL_ID retained for audit';
