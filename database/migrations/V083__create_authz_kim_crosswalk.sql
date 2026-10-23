-- KIM principal crosswalk for server-side identity enrollment (authorization
-- design rev 3, sections 12.2 Option A and 13). ADDITIVE: two new, EMPTY
-- tables in the private "authz" schema (V082). No real data is loaded here;
-- rows arrive only through the audited administration CLI
-- (scripts/authz-admin/authz_admin.py crosswalk-import).
--
-- What the crosswalk is:
--   ONE verified sign-in attribute value (the single attribute the
--   institution's identity provider releases and the deployment configures)
--   -> exactly one ACTIVE crosswalk row
--   -> an existing, ACTIVE KIM principal (KRIM_PRNCPL_T.PRNCPL_ID, which is
--      the PERSON_ID archived on Award/Proposal contact rows)
--   -> contact-derived access (policy VERIFIED_PRINCIPAL), never a grant.
--
-- What it is NOT:
--   - no email mapping and no login-name (PRNCPL_NM) mapping: neither is
--     stored here, and neither is ever used to find a person;
--   - non-employee (rolodex) contact ids are never principals: a crosswalk
--     row must reference a row in authz.kim_principal (foreign key), and the
--     import validator refuses rolodex ids and email-like values;
--   - it never creates grants. Central, Department and IO access stay
--     explicit authz.access_grant rows.
--
-- kim_principal holds only the minimal columns needed to decide "is this an
-- active principal": PRNCPL_ID, ENTITY_ID (ambiguity checks only) and ACTV_IND.

CREATE SCHEMA IF NOT EXISTS authz;

CREATE TABLE IF NOT EXISTS authz.kim_principal (
    prncpl_id  TEXT PRIMARY KEY,
    entity_id  TEXT NOT NULL,
    actv_ind   CHAR(1) NOT NULL,
    loaded_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_ref   TEXT NOT NULL,

    CONSTRAINT ck_kim_principal_actv_ind CHECK (actv_ind IN ('Y', 'N'))
);

CREATE TABLE IF NOT EXISTS authz.principal_crosswalk (
    crosswalk_id     BIGSERIAL PRIMARY KEY,
    attribute_name   TEXT NOT NULL,
    attribute_value  TEXT NOT NULL,
    prncpl_id        TEXT NOT NULL REFERENCES authz.kim_principal (prncpl_id),
    status           VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    evidence_ref     TEXT NOT NULL,
    verified_by      TEXT NOT NULL,
    verified_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_by       TEXT,
    revoked_at       TIMESTAMPTZ,

    CONSTRAINT ck_principal_crosswalk_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_principal_crosswalk_revoked CHECK (
        (status = 'REVOKED') = (revoked_at IS NOT NULL)
        AND (revoked_at IS NULL) = (revoked_by IS NULL)
    )
);

-- Uniqueness both ways among ACTIVE rows: one value -> one principal, and
-- one principal <- one value. Revoked rows are history and may repeat.
CREATE UNIQUE INDEX IF NOT EXISTS ux_principal_crosswalk_active_value
    ON authz.principal_crosswalk (attribute_name, attribute_value)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX IF NOT EXISTS ux_principal_crosswalk_active_principal
    ON authz.principal_crosswalk (attribute_name, prncpl_id)
    WHERE status = 'ACTIVE';
