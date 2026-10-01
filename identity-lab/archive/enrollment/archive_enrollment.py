"""ARCHIVE-SPECIFIC post-sign-in hook: the lab's simulation of the archive's
enrollment step (authorization design section 12.2), using the KIM-based chain:
verified sign-in attribute -> unique ACTIVE crosswalk row -> ACTIVE KIM principal
(PRNCPL_ID = contact PERSON_ID) -> authz.identity_link. Not implemented in the
API. Links a simulated-Cognito profile (iss, sub) to an archive person only
when every check passes; otherwise it records why and links nothing, so the
API answers ACCESS_NOT_PROVISIONED (fail closed).

It never creates grants. Grants exist only in authz.access_grant, and
historical Kuali roles (lab_evidence.kuali_role_evidence) are never read here.
"""
import json
import os

import psycopg
from psycopg.rows import dict_row

DSN = os.environ["ARCHIVE_DATABASE_URL"]
VERIFIER = "lab-enrollment (simulated design s12.2)"


def enroll(issuer, provider, profile):
    sub = str(profile["sub"])
    inst = (profile.get("attributes") or {}).get("custom:institutional_id")
    with psycopg.connect(DSN, row_factory=dict_row) as c:
        def record(outcome, **detail):
            c.execute(
                "INSERT INTO identity_lab.enrollment_event "
                "(cognito_issuer, cognito_subject, institutional_identifier, outcome, detail) "
                "VALUES (%s, %s, %s, %s, %s)", (issuer, sub, inst, outcome, json.dumps(detail)))
            c.commit()
            return {"outcome": outcome, **detail}

        federated = any(i.get("providerName") == provider for i in profile.get("identities") or [])
        if not federated:
            return record("REFUSED_NOT_FEDERATED")

        links = c.execute(
            "SELECT * FROM authz.identity_link WHERE cognito_issuer = %s AND cognito_subject = %s",
            (issuer, sub)).fetchall()
        active = [l for l in links if l["status"] == "ACTIVE"]
        if active:
            link = active[0]
            if inst != link["institutional_identifier"]:
                # e.g. the IdP now sends a different identifier for this profile:
                # never move the link; revoke it and require re-verification.
                c.execute("UPDATE authz.identity_link SET status = 'REVOKED', revoked_by = %s, revoked_at = now() "
                          "WHERE identity_link_id = %s", (VERIFIER, link["identity_link_id"]))
                return record("REVOKED_IDENTIFIER_MISMATCH", linked=link["institutional_identifier"])
            current = c.execute(
                "SELECT 1 FROM identity_lab.principal_crosswalk x JOIN identity_lab.kim_principal p "
                "ON p.prncpl_id = x.prncpl_id WHERE x.attribute_name = 'labInstitutionalId' "
                "AND x.attribute_value = %s AND x.status = 'ACTIVE' AND p.actv_ind = 'Y' AND p.prncpl_id = %s",
                (inst, link["kuali_person_id"])).fetchall()
            if len(current) != 1:
                c.execute("UPDATE authz.identity_link SET status = 'REVOKED', revoked_by = %s, revoked_at = now() "
                          "WHERE identity_link_id = %s", (VERIFIER, link["identity_link_id"]))
                return record("REVOKED_MAPPING_NO_LONGER_VALID", linked=link["kuali_person_id"])
            return record("ALREADY_LINKED")
        if links:
            return record("REFUSED_PREVIOUSLY_REVOKED")
        if not inst:
            return record("REFUSED_MISSING_IDENTIFIER")
        # verified attribute -> exactly one ACTIVE crosswalk row -> an ACTIVE KIM principal.
        # No email or login-name fallback, ever (login-name mapping needs confirmed
        # lifecycle/reassignment handling first).
        rows = c.execute("SELECT prncpl_id FROM identity_lab.principal_crosswalk "
                         "WHERE attribute_name = 'labInstitutionalId' AND attribute_value = %s AND status = 'ACTIVE'",
                         (inst,)).fetchall()
        if not rows:
            return record("REFUSED_UNKNOWN_PERSON")
        if len({r["prncpl_id"] for r in rows}) > 1:
            return record("REFUSED_AMBIGUOUS_MAPPING", candidates=len(rows))
        principal = c.execute("SELECT prncpl_id, actv_ind FROM identity_lab.kim_principal WHERE prncpl_id = %s",
                              (rows[0]["prncpl_id"],)).fetchone()
        if not principal:
            # e.g. a rolodex (non-employee) id: never a login account
            return record("REFUSED_NOT_A_KIM_PRINCIPAL")
        if principal["actv_ind"] != "Y":
            return record("REFUSED_INACTIVE_PRINCIPAL")
        person = {"kuali_person_id": principal["prncpl_id"]}
        other = c.execute(
            "SELECT 1 FROM authz.identity_link WHERE cognito_issuer = %s AND institutional_identifier = %s "
            "AND status = 'ACTIVE'", (issuer, inst)).fetchone()
        if other:
            return record("REFUSED_IDENTIFIER_LINKED_TO_ANOTHER_PROFILE")
        c.execute(
            "INSERT INTO authz.identity_link (cognito_issuer, cognito_subject, institutional_identifier, "
            "kuali_person_id, login_name, method, status, verified_by, verified_at) "
            "VALUES (%s, %s, %s, %s, %s, 'AUTO_VERIFIED', 'ACTIVE', %s, now())",
            (issuer, sub, inst, person["kuali_person_id"],
             (profile.get("attributes") or {}).get("custom:login"), VERIFIER))
        return record("LINKED", kuali_person_id=person["kuali_person_id"])
