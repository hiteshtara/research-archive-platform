-- The smallest safe change for the archive-issued identity model.
--
-- authz.identity_link already guarantees ONE active link per Cognito identity
-- (ux_identity_link_active_cognito). It does NOT guarantee the reverse: two
-- different Cognito subjects could both hold active links to the SAME
-- institutional_identifier, and both would resolve to it and inherit every
-- grant it holds.
--
-- IdentityResolver does not catch that. Its ambiguity check asks
-- activePersonIdsFor(identifier), whose SQL filters "kuali_person_id IS NOT
-- NULL" - and an administrator-verified link has no Kuali principal, so the
-- check sees nothing and returns Mapped.
--
-- Today this is mitigated only by administrator care. Under the archive-issued
-- identity model it becomes materially more dangerous: a BU-issued identifier
-- can at least be cross-checked against an external source, while an
-- archive-minted opaque id has nothing to check against. So the database must
-- hold the invariant.
--
-- Partial, so it constrains only ACTIVE rows. Revoked links are history and may
-- repeat - which is what lets an identity be re-linked after a Cognito user
-- pool is replaced: revoke the old link, then create the new one.
--
-- Additive and reversible: it creates one index and alters no data. It will
-- FAIL if the table already holds a violation, which is the correct outcome -
-- that would mean two people already share one archive identity, and it must
-- be resolved deliberately rather than indexed over.
CREATE UNIQUE INDEX IF NOT EXISTS ux_identity_link_active_identity
    ON authz.identity_link (institutional_identifier)
    WHERE status = 'ACTIVE';
