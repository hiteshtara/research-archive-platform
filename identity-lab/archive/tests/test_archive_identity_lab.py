"""End-to-end tests of the running local SAML lab (scripts/identity-lab/start.sh).

Every test signs in through the real Shibboleth IdP with a FICTIONAL account,
gets tokens from the simulated Cognito, and calls the archive API, whose
production SecurityConfiguration validates the token and whose record
enforcement decides what is visible. A successful login is never treated as
authorization: each test asserts what the API actually returns.
"""
import base64
import html
import json
import re
import subprocess
import time
from pathlib import Path
from urllib.parse import urljoin

import pytest
import requests

import lab_client as c
from lab_client import SignInFailed, api, login

ROOT = Path(__file__).resolve().parents[3]
ADMIN = str(ROOT / "scripts" / "identity-lab" / "admin.sh")

A, A_CHILD, B, C, D, E, F, G, H, I, J = (
    "990001-00001", "990001-00002", "990002-00001", "990003-00001", "990004-00001",
    "990005-00001", "990006-00001", "990007-00001", "990008-00001", "990009-00001", "990010-00001")
ALL_FAMILIES = {A, A_CHILD, B, C, D, E, F, G, H, I, J}
PASSWORDS = {
    "lab-central": "Lab-Central-2026", "lab-dept": "Lab-Dept-2026", "lab-pat": "Lab-Pat-2026",
    "lab-io": "Lab-Io-2026", "lab-multi": "Lab-Multi-2026", "lab-nogrants": "Lab-Nogrants-2026",
    "lab-suspended": "Lab-Suspended-2026", "lab-noattr": "Lab-Noattr-2026", "lab-stranger": "Lab-Stranger-2026",
}


def admin(*args):
    subprocess.run([ADMIN, *args], check=True, capture_output=True)


def sql(container, user, db, query):
    out = subprocess.run(["docker", "exec", container, "psql", "-U", user, "-d", db, "-Atc", query],
                         check=True, capture_output=True, text=True)
    return out.stdout.strip()


def archive_sql(query):
    return sql("lab-archive-db", "lab_archive", "identity_lab", query)


def pool_sql(query):
    return sql("lab-pool-db", "lab_pool", "lab_pool", query)


def claims(token):
    return json.loads(base64.urlsafe_b64decode(token.split(".")[1] + "=="))


def sign_in(user, password=None):
    tokens, _ = login(user, password or PASSWORDS[user])
    return tokens["access_token"]


def families(token):
    r = api(token, "/api/v1/awards/search?size=100")
    assert r.status_code == 200, r.text
    page = r.json()["results"]
    numbers = {x["awardNumber"] for x in page["content"]}
    assert page["totalElements"] == len(numbers), "count must match the scoped page"
    return numbers


def status(token, path):
    return api(token, path).status_code


def kinds(token):
    """Grant kinds from /api/v1/me/access (exempt from enforcement, always 200)."""
    body = api(token, "/api/v1/me/access").json()
    assert body["problem"] is None, body
    return body["grantKinds"]


def refused(token):
    """(HTTP status and code on a record path, problem reported by /api/v1/me/access)."""
    status_body = api(token, "/api/v1/me/access").json()
    r = api(token, "/api/v1/awards/search?size=100")
    return r.status_code, r.json().get("code"), status_body["problem"]


def latest_enrollment(inst=None):
    where = f"WHERE institutional_identifier = '{inst}'" if inst else ""
    return archive_sql(f"SELECT outcome FROM identity_lab.enrollment_event {where} ORDER BY event_id DESC LIMIT 1")


# ------------------------------------------------------------- roles

def test_pi_sees_only_contact_records():
    t = sign_in("lab-pat")
    assert kinds(t) == ["RESEARCH_STAFF"]
    assert families(t) == {A, C, D, I}                    # PI, Co-PI (MPI), COI; I via seq 2
    assert status(t, "/api/v1/awards/by-number/990001-00001") == 200
    assert status(t, "/api/v1/awards/by-number/990002-00001") == 404   # B: other unit, not a contact
    assert status(t, "/api/v1/awards/9000501/summary") == 404          # E: KP excluded
    assert status(t, "/api/v1/awards/9000111/summary") == 404          # A's child: not inherited
    # Approved 2026-10-01: record access covers its files - no ArchiveAttachmentViewer group needed.
    assert status(t, "/api/v1/awards/9000102/attachments") == 200
    r = api(t, "/api/v1/awards/9000102/attachments/9300001/download")
    assert r.status_code == 200 and r.content.startswith(b"%PDF") and b"FICTIONAL" in r.content
    assert status(t, "/api/v1/awards/9000201/attachments/9300002/download") == 404   # B: not authorized
    assert status(t, "/api/v1/awards/9000102/attachments/9300002/download") == 404   # B's file via A's URL
    assert status(t, "/api/v1/awards/9000201/report.pdf") == 404
    assert status(t, "/api/v1/awards/9000102/report.pdf") == 200
    assert api(t, "/api/v1/awards/9000102/funding-proposals").json() == []


def test_department_sees_only_its_unit():
    t = sign_in("lab-dept")
    assert kinds(t) == ["DEPARTMENT"]
    assert families(t) == {A, A_CHILD, I}
    assert status(t, "/api/v1/awards/by-number/990002-00001") == 404
    assert status(t, "/api/v1/awards/9000701/summary") == 404          # sub-unit: exact match (P6 demo setting)
    assert status(t, "/api/v1/awards/9000102/attachments") == 200      # has the attachment group


def test_central_sees_everything():
    t = sign_in("lab-central")
    assert kinds(t) == ["CENTRAL"]
    assert families(t) == ALL_FAMILIES
    assert status(t, "/api/v1/awards/by-number/990002-00001") == 200


def test_io_user_sees_only_the_granted_io():
    t = sign_in("lab-io")
    assert kinds(t) == ["OTHER_AUTHORIZED_VIEWER"]
    assert families(t) == {F, I}
    assert status(t, "/api/v1/awards/9000801/summary") == 404          # H carries another IO


def test_multiple_grants_are_a_union():
    assert families(sign_in("lab-multi")) == {C, D, E, H, J}


def test_unfinished_paths_stay_closed_for_non_central_users():
    t = sign_in("lab-dept")
    for path in ("/api/negotiations/search?q=SYNTHETIC", "/api/v1/documents?q=SYNTHETIC"):
        assert status(t, path) in (403, 404), path


# ------------------------------------------------- identity stability

def test_repeated_login_gives_same_nameid_profile_and_person():
    first, second = sign_in("lab-pat"), sign_in("lab-pat")
    assert claims(first)["username"] == claims(second)["username"]   # same NameID
    assert claims(first)["sub"] == claims(second)["sub"]
    assert archive_sql(
        f"SELECT count(*) FROM authz.identity_link WHERE cognito_subject = '{claims(first)['sub']}' "
        "AND status = 'ACTIVE' AND institutional_identifier = 'SYN-INST-0003'") == "1"
    assert latest_enrollment("SYN-INST-0003") == "ALREADY_LINKED"


def test_changed_login_name_same_institutional_id_is_the_same_person():
    before = sign_in("lab-pat")
    admin("rename-login", "lab-pat", "lab-pat-renamed")
    try:
        after = sign_in("lab-pat-renamed", PASSWORDS["lab-pat"])
        assert claims(after)["username"] == claims(before)["username"]   # NameID from institutional id
        assert claims(after)["sub"] == claims(before)["sub"]
        assert families(after) == {A, C, D, I}
        assert pool_sql(f"SELECT attributes->>'custom:login' FROM user_profile "
                        f"WHERE sub = '{claims(after)['sub']}'") == "lab-pat-renamed"
    finally:
        admin("rename-login", "lab-pat-renamed", "lab-pat")


def test_reused_login_name_with_different_institutional_id_is_a_different_person():
    original = sign_in("lab-pat")
    admin("rename-login", "lab-pat", "lab-pat-old")
    admin("add-account", "lab-pat", "Lab-Reuse-2026", "SYN-INST-0010", "Rene Reuse (FICTIONAL)")
    try:
        reused = sign_in("lab-pat", "Lab-Reuse-2026")
        assert claims(reused)["username"] != claims(original)["username"]
        assert claims(reused)["sub"] != claims(original)["sub"]
        assert latest_enrollment("SYN-INST-0010") in ("LINKED", "ALREADY_LINKED")
        # no grants of its own, and nothing inherited from Pat
        assert refused(reused) == (403, "ACCESS_NOT_PROVISIONED", "ACCESS_NOT_PROVISIONED")
        assert status(reused, "/api/v1/awards/9000102/summary") == 403
    finally:
        admin("remove-account", "lab-pat")
        admin("rename-login", "lab-pat-old", "lab-pat")


def test_transient_nameid_changes_the_profile_and_fails_closed():
    admin("nameid", "transient")
    try:
        one, two = sign_in("lab-io"), sign_in("lab-io")
        assert claims(one)["sub"] != claims(two)["sub"]
        assert latest_enrollment("SYN-INST-0005") == "REFUSED_IDENTIFIER_LINKED_TO_ANOTHER_PROFILE"
        assert refused(two) == (403, "ACCESS_NOT_PROVISIONED", "ACCESS_NOT_PROVISIONED")
    finally:
        admin("nameid", "persistent")
    assert families(sign_in("lab-io")) == {F, I}


# -------------------------------------------- missing / unknown / status

def test_missing_institutional_id_attribute_cannot_sign_in():
    # The persistent NameID is computed from the institutional identifier, so
    # without it the IdP cannot satisfy the requested NameID format.
    with pytest.raises(SignInFailed):
        sign_in("lab-noattr")


def test_person_not_in_crosswalk_is_not_provisioned():
    t = sign_in("lab-stranger")
    assert latest_enrollment("SYN-INST-0099") == "REFUSED_UNKNOWN_PERSON"
    assert refused(t) == (403, "ACCESS_NOT_PROVISIONED", "ACCESS_NOT_PROVISIONED")


def test_suspended_person_is_denied():
    assert refused(sign_in("lab-suspended")) == (403, "ACCESS_DENIED", "ACCESS_DENIED")


def test_live_suspension_applies_to_an_already_issued_token():
    t = sign_in("lab-dept")
    assert families(t) == {A, A_CHILD, I}
    admin("suspend", "SYN-INST-0002")
    try:
        assert refused(t) == (403, "ACCESS_DENIED", "ACCESS_DENIED")
        assert status(t, "/api/v1/awards/9000102/summary") == 403
    finally:
        admin("unsuspend", "SYN-INST-0002")
    assert families(t) == {A, A_CHILD, I}


def test_live_grant_revocation_applies_to_an_already_issued_token():
    t = sign_in("lab-central")
    admin("revoke-grant", "SYN-INST-0001", "CENTRAL")
    try:
        assert refused(t) == (403, "ACCESS_NOT_PROVISIONED", "ACCESS_NOT_PROVISIONED")
        assert status(t, "/api/v1/awards/by-number/990002-00001") == 403
    finally:
        admin("restore-grant", "SYN-INST-0001", "CENTRAL")
    assert families(t) == ALL_FAMILIES


def test_kim_principal_pi_sees_award_without_any_grant():
    t = sign_in("lab-kim-pi", "Lab-Kim-Pi-2026")
    assert archive_sql("SELECT count(*) FROM authz.access_grant WHERE institutional_identifier = 'SYN-INST-0011'") == "0"
    assert kinds(t) == ["RESEARCH_STAFF"]
    assert families(t) == {J}
    assert status(t, "/api/v1/awards/9001001/summary") == 200
    assert status(t, "/api/v1/awards/9000201/summary") == 404


def test_kim_account_alone_grants_nothing():
    t = sign_in("lab-kim-only", "Lab-Kim-Only-2026")
    assert refused(t) == (403, "ACCESS_NOT_PROVISIONED", "ACCESS_NOT_PROVISIONED")


def test_kuali_role_evidence_never_grants_access():
    assert archive_sql("SELECT count(*) FROM lab_evidence.kuali_role_evidence") == "2"
    # Pat's fictional Kuali role on SYN-U-200 would cover B in Kuali; not here.
    assert B not in families(sign_in("lab-pat"))
    # A fictional university-wide Kuali role, and no archive grant: no access.
    assert refused(sign_in("lab-nogrants")) == (403, "ACCESS_NOT_PROVISIONED", "ACCESS_NOT_PROVISIONED")


# ----------------------------------------------------------- logout

def test_logout_ends_refresh_and_idp_session_but_not_issued_access_tokens():
    tokens, browser = login("lab-dept", PASSWORDS["lab-dept"])
    # Amplify signOut({global: true}) -> GlobalSignOut on the simulated user pool.
    r = requests.post(c.COGNITO + "/", verify=c.CA, json={"AccessToken": tokens["access_token"]},
                      headers={"X-Amz-Target": "AWSCognitoIdentityProviderService.GlobalSignOut",
                               "Content-Type": "application/x-amz-json-1.1"})
    assert r.status_code == 200
    refreshed = requests.post(c.COGNITO + "/oauth2/token", verify=c.CA, data={
        "grant_type": "refresh_token", "client_id": c.CLIENT_ID, "refresh_token": tokens["refresh_token"]})
    assert refreshed.status_code == 400
    # Hosted-UI /logout -> SAML LogoutRequest to the IdP -> LogoutResponse -> back to the UI.
    r = browser.get(c.COGNITO + "/logout", params={"client_id": c.CLIENT_ID, "logout_uri": c.REDIRECT},
                    allow_redirects=True)
    hops = [h.url for h in r.history] + [r.url]
    # The IdP's logout-complete page (lab view) continues in the main window.
    cont = re.search(r'id="lab-continue" href="([^"]+)"', r.text)
    assert cont, "IdP logout page should offer to return to the application"
    r = browser.get(urljoin(r.url, html.unescape(cont.group(1))), allow_redirects=False)
    while r.status_code in (301, 302, 303) and not r.headers["Location"].startswith(c.REDIRECT):
        hops.append(r.headers["Location"])
        r = browser.get(urljoin(r.url, r.headers["Location"]), allow_redirects=False)
    assert r.status_code in (301, 302, 303)
    hops.append(r.headers["Location"])
    assert any("/idp/profile/SAML2/Redirect/SLO" in u for u in hops)
    assert hops[-1] == c.REDIRECT
    assert pool_sql("SELECT detail->>'status' FROM pool_event WHERE kind = 'SAML_LOGOUT_COMPLETED' "
                    "ORDER BY event_id DESC LIMIT 1") == "urn:oasis:names:tc:SAML:2.0:status:Success"
    # The IdP session is gone: the same browser must enter the password again.
    r = browser.get(c.COGNITO + "/oauth2/authorize", params={
        "client_id": c.CLIENT_ID, "redirect_uri": c.REDIRECT, "response_type": "code", "state": "x",
        "code_challenge": "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", "code_challenge_method": "S256"})
    assert "j_password" in r.text
    # A JWT already issued stays valid until it expires (Cognito behaves the same).
    assert status(tokens["access_token"], "/api/v1/me/access") == 200


# --------------------------------------------------- token validation

def test_api_rejects_tokens_not_from_the_lab_pool():
    from cryptography.hazmat.primitives.asymmetric import rsa
    import jwt
    good = claims(sign_in("lab-central"))
    other_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    forged = jwt.encode(good, other_key, algorithm="RS256", headers={"kid": "forged"})
    assert status(forged, "/api/v1/me/access") == 401
    tokens, _ = login("lab-central", PASSWORDS["lab-central"])
    assert status(tokens["id_token"], "/api/v1/me/access") == 401     # ID token, not an access token
    assert status("not-a-jwt", "/api/v1/me/access") == 401


# -------------------------------------------------- network isolation

def test_lab_services_have_no_route_off_the_docker_host():
    net = json.loads(subprocess.run(["docker", "network", "inspect", "identity-lab_lab"],
                                    check=True, capture_output=True, text=True).stdout)[0]
    assert net["Internal"] is True
    probes = {
        "lab-idp": ["curl", "-s", "-m", "5", "-o", "/dev/null", "https://collector.testbed.tier.internet2.edu:5001/"],
        "lab-ldap": ["wget", "-q", "-T", "5", "-O", "/dev/null", "http://1.1.1.1/"],
        "lab-cognito": ["python", "-c", "import socket; socket.create_connection(('1.1.1.1', 443), 5)"],
    }
    for container, probe in probes.items():
        result = subprocess.run(["docker", "exec", container, *probe], capture_output=True)
        assert result.returncode != 0, f"{container} reached the internet"
