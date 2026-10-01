"""Local SAML integration - Cognito simulated.

A SIMULATION of the parts of an Amazon Cognito user pool this archive uses:
SAML federation (as the SP), user-pool profiles, the hosted-UI OAuth2
endpoints, signed access/ID tokens, and the user-pool API calls Amplify
makes (refresh, global sign-out, revoke). It is a test component for the
local identity lab only - it is not Cognito, and it never contacts AWS.

Application-neutral: anything specific to one application (fixtures, account
linking) is supplied from outside through hooks.py.

Behaviour copied from Cognito on purpose (see identity-lab/TRUST_FLOW.md):
- federated username = <provider>_<NameID>; sub generated once per profile;
- mapped attributes overwritten on every sign-in; `identities` records the
  provider and NameID;
- access token: token_use=access, client_id, username, cognito:groups.
"""
import base64
import hashlib
import json
import os
import secrets
import time
import uuid
from urllib.parse import urlencode

import jwt
from cryptography.hazmat.primitives import serialization
from flask import Flask, Response, abort, jsonify, redirect, request
from saml2 import BINDING_HTTP_POST, BINDING_HTTP_REDIRECT
from saml2.client import Saml2Client
from saml2.config import SPConfig
from saml2.saml import NameID

import db
import hooks

CREDS = os.environ.get("LAB_CREDS", "/lab/creds")
POOL_ID = os.environ.get("LAB_POOL_ID", "us-east-1_LabSimSaml")
PUBLIC_BASE = os.environ.get("LAB_PUBLIC_BASE", "https://localhost:9443")
ISSUER = f"{PUBLIC_BASE}/{POOL_ID}"
CLIENT_ID = os.environ.get("LAB_CLIENT_ID", "labsimulatedclient")
UI_ORIGIN = os.environ.get("LAB_UI_ORIGIN", "http://localhost:5198")
REDIRECT_URIS = {f"{UI_ORIGIN}/"}
LOGOUT_URIS = {f"{UI_ORIGIN}/"}
PROVIDER = "LabShibboleth"
SP_ENTITY = f"urn:lab:simulated-cognito:sp:{POOL_ID}"
IDP_ENTITY = "https://idp.lab.invalid/idp/shibboleth"
IDP_SLO = "https://localhost:8443/idp/profile/SAML2/Redirect/SLO"
ACCESS_TTL = int(os.environ.get("LAB_ACCESS_TTL_SECONDS", "600"))
REFRESH_TTL = 3600
CODE_TTL = 120
SESSION_COOKIE = "lab_cognito_session"

ATTR = {
    "urn:oid:0.9.2342.19200300.100.1.1": "custom:login",              # uid
    "urn:oid:1.3.6.1.4.1.5923.1.1.1.6": "custom:eppn",                 # eduPersonPrincipalName
    "urn:oid:0.9.2342.19200300.100.1.3": "email",                      # mail
    "urn:oid:2.16.840.1.113730.3.1.241": "name",                       # displayName
    "urn:lab:attribute:institutionalId": "custom:institutional_id",   # lab-only name
}
FORMATS = {
    "persistent": "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent",
    "transient": "urn:oasis:names:tc:SAML:2.0:nameid-format:transient",
}

app = Flask(__name__)

# The pool's own schema, and optional group fixtures supplied by an application
# (stand-in for an administrator's AdminAddUserToGroup after first sign-in).
db.cognito_exec(open("/srv/schema.sql").read())
_GROUP_SEED = os.environ.get("LAB_GROUP_SEED_FILE", "")
if _GROUP_SEED and os.path.exists(_GROUP_SEED):
    for _line in open(_GROUP_SEED):
        if _line.strip() and not _line.startswith("#"):
            _login, _group = _line.rstrip("\n").split("\t")[:2]
            db.cognito_exec("INSERT INTO group_seed VALUES (%s, %s) ON CONFLICT DO NOTHING", (_login, _group))

with open(f"{CREDS}/token-signing.key", "rb") as f:
    SIGNING_KEY = serialization.load_pem_private_key(f.read(), password=None)
KID = hashlib.sha256(
    SIGNING_KEY.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
).hexdigest()[:16]


def saml_client():
    cfg = SPConfig()
    cfg.load({
        "entityid": SP_ENTITY,
        "xmlsec_binary": "/usr/bin/xmlsec1",
        "key_file": f"{CREDS}/sp-signing.key",
        "cert_file": f"{CREDS}/sp-signing.crt",
        "encryption_keypairs": [{"key_file": f"{CREDS}/sp-encryption.key",
                                 "cert_file": f"{CREDS}/sp-encryption.crt"}],
        "metadata": {"local": [f"{CREDS}/idp-metadata.xml"]},
        "allow_unknown_attributes": True,
        "service": {"sp": {
            "endpoints": {
                "assertion_consumer_service": [(f"{PUBLIC_BASE}/saml2/idpresponse", BINDING_HTTP_POST)],
                "single_logout_service": [(f"{PUBLIC_BASE}/saml2/logout", BINDING_HTTP_REDIRECT)],
            },
            "authn_requests_signed": True,
            "logout_requests_signed": True,
            "want_assertions_signed": True,
            "want_response_signed": False,
            "allow_unsolicited": False,
        }},
    })
    return Saml2Client(config=cfg)


SAML = saml_client()


# ---------------------------------------------------------------- tokens

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def sign(claims):
    return jwt.encode(claims, SIGNING_KEY, algorithm="RS256", headers={"kid": KID})


def issue_tokens(profile, auth_time, with_refresh=True):
    now = int(time.time())
    groups = db.cognito_all("SELECT group_name FROM user_group WHERE username = %s ORDER BY 1",
                            (profile["username"],))
    groups = [g["group_name"] for g in groups]
    origin = str(uuid.uuid4())
    access = {
        "sub": str(profile["sub"]), "iss": ISSUER, "version": 2, "client_id": CLIENT_ID,
        "origin_jti": origin, "event_id": str(uuid.uuid4()), "token_use": "access",
        "scope": "openid email profile", "auth_time": auth_time, "exp": now + ACCESS_TTL,
        "iat": now, "jti": str(uuid.uuid4()), "username": profile["username"],
    }
    attrs = profile["attributes"]
    idt = {
        "sub": str(profile["sub"]), "iss": ISSUER, "cognito:username": profile["username"],
        "origin_jti": origin, "aud": CLIENT_ID, "token_use": "id", "auth_time": auth_time,
        "exp": now + ACCESS_TTL, "iat": now, "jti": str(uuid.uuid4()),
        "identities": profile["identities"],
    }
    if groups:
        access["cognito:groups"] = groups
        idt["cognito:groups"] = groups
    for k in ("email", "name", "custom:institutional_id", "custom:login"):
        if k in attrs:
            idt[k] = attrs[k]
    out = {"access_token": sign(access), "id_token": sign(idt),
           "token_type": "Bearer", "expires_in": ACCESS_TTL}
    if with_refresh:
        rt = secrets.token_urlsafe(48)
        db.cognito_exec(
            "INSERT INTO refresh_token (token_hash, username, auth_time, expires_at) "
            "VALUES (%s, %s, %s, now() + make_interval(secs => %s))",
            (hashlib.sha256(rt.encode()).hexdigest(), profile["username"], auth_time, REFRESH_TTL))
        out["refresh_token"] = rt
    return out


def profile_by_username(username):
    return db.cognito_one("SELECT * FROM user_profile WHERE username = %s", (username,))


def verify_own_access_token(token):
    try:
        claims = jwt.decode(token, SIGNING_KEY.public_key(), algorithms=["RS256"], issuer=ISSUER)
    except jwt.PyJWTError:
        return None
    return claims if claims.get("token_use") == "access" else None


# ---------------------------------------------------------- discovery

@app.get(f"/{POOL_ID}/.well-known/openid-configuration")
def discovery():
    return jsonify({
        "issuer": ISSUER,
        "jwks_uri": f"{ISSUER}/.well-known/jwks.json",
        "authorization_endpoint": f"{PUBLIC_BASE}/oauth2/authorize",
        "token_endpoint": f"{PUBLIC_BASE}/oauth2/token",
        "response_types_supported": ["code"],
        "subject_types_supported": ["public"],
        "id_token_signing_alg_values_supported": ["RS256"],
    })


@app.get(f"/{POOL_ID}/.well-known/jwks.json")
def jwks():
    jwk = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(SIGNING_KEY.public_key()))
    jwk.update({"kid": KID, "alg": "RS256", "use": "sig"})
    return jsonify({"keys": [jwk]})


# ---------------------------------------------------------- hosted UI

def lab_page(title, body, status=200):
    html = f"""<!doctype html><html><head><meta charset="utf-8"><title>{title}</title>
<style>body{{font:16px system-ui;margin:40px;max-width:640px}}.b{{background:#7a1f1f;color:#fff;padding:8px 12px}}</style>
</head><body><div class="b">Local SAML integration&mdash;Cognito simulated</div><h1>{title}</h1>{body}</body></html>"""
    return Response(html, status=status, mimetype="text/html")


@app.get("/oauth2/authorize")
def authorize():
    a = request.args
    if a.get("client_id") != CLIENT_ID:
        return lab_page("Invalid client", "<p>Unknown client_id.</p>", 400)
    if a.get("redirect_uri") not in REDIRECT_URIS:
        return lab_page("Invalid redirect", "<p>redirect_uri is not registered.</p>", 400)
    if a.get("response_type") != "code" or a.get("code_challenge_method") != "S256" or not a.get("code_challenge"):
        return lab_page("Invalid request", "<p>Authorization code with PKCE (S256) is required.</p>", 400)
    mode = db.setting("nameid_mode", "persistent")
    relay = secrets.token_urlsafe(24)
    req_id, info = SAML.prepare_for_authenticate(
        entityid=IDP_ENTITY, relay_state=relay, binding=BINDING_HTTP_REDIRECT,
        nameid_format=FORMATS[mode], sign=True)
    db.cognito_exec(
        "INSERT INTO pending_auth (relay_state, saml_request_id, redirect_uri, state, code_challenge, scope, nameid_mode) "
        "VALUES (%s, %s, %s, %s, %s, %s, %s)",
        (relay, req_id, a["redirect_uri"], a.get("state", ""), a["code_challenge"], a.get("scope", ""), mode))
    return redirect(dict(info["headers"])["Location"])


@app.post("/saml2/idpresponse")
def acs():
    relay = request.form.get("RelayState", "")
    pending = db.cognito_one("DELETE FROM pending_auth WHERE relay_state = %s RETURNING *", (relay,))
    if not pending:
        return lab_page("Sign-in failed", "<p>Unknown or already-used sign-in attempt.</p>", 400)
    try:
        resp = SAML.parse_authn_request_response(
            request.form["SAMLResponse"], BINDING_HTTP_POST,
            outstanding={pending["saml_request_id"]: "/"})
    except Exception as e:  # signature, audience, replay, status - all fail closed
        db.event("SAML_REJECTED", None, {"error": type(e).__name__})
        return lab_page("Sign-in failed", f"<p>The SAML response was rejected ({type(e).__name__}).</p>", 400)
    if resp is None or resp.name_id is None or not resp.name_id.text:
        db.event("SAML_REJECTED", None, {"error": "NoNameID"})
        return lab_page("Sign-in failed", "<p>The assertion carried no NameID.</p>", 400)

    name_id = resp.name_id
    attributes = {}
    for statement in resp.assertion.attribute_statement:
        for attribute in statement.attribute:
            target = ATTR.get(attribute.name)
            values = [v.text for v in attribute.attribute_value if v.text]
            if target and values:
                attributes[target] = values[0]
    authn = resp.assertion.authn_statement[0]
    profile = upsert_profile(name_id, attributes)
    outcome = hooks.post_sign_in(ISSUER, PROVIDER, profile)

    session_id = secrets.token_urlsafe(24)
    db.cognito_exec(
        "INSERT INTO saml_session (session_id, username, name_id, name_id_format, name_qualifier, "
        "sp_name_qualifier, session_index) VALUES (%s, %s, %s, %s, %s, %s, %s)",
        (session_id, profile["username"], name_id.text, name_id.format, name_id.name_qualifier,
         name_id.sp_name_qualifier, authn.session_index))
    code = secrets.token_urlsafe(32)
    db.cognito_exec(
        "INSERT INTO auth_code (code_hash, username, redirect_uri, code_challenge, auth_time, expires_at) "
        "VALUES (%s, %s, %s, %s, %s, now() + make_interval(secs => %s))",
        (hashlib.sha256(code.encode()).hexdigest(), profile["username"], pending["redirect_uri"],
         pending["code_challenge"], int(time.time()), CODE_TTL))
    db.event("SIGNED_IN", profile["username"],
             {"sub": str(profile["sub"]), "nameid_format": name_id.format, "hook": outcome})
    target = pending["redirect_uri"] + "?" + urlencode({"code": code, "state": pending["state"]})
    out = redirect(target)
    out.set_cookie(SESSION_COOKIE, session_id, secure=True, httponly=True, samesite="Lax")
    return out


def upsert_profile(name_id, attributes):
    username = f"{PROVIDER}_{name_id.text}"
    identities = [{
        "userId": name_id.text, "providerName": PROVIDER, "providerType": "SAML",
        "issuer": None, "primary": "true", "dateCreated": str(int(time.time() * 1000)),
    }]
    existing = profile_by_username(username)
    if existing:
        db.cognito_exec(
            "UPDATE user_profile SET attributes = %s, updated_at = now() WHERE username = %s",
            (json.dumps(attributes), username))
        existing["attributes"] = attributes
        return existing
    sub = str(uuid.uuid4())
    db.cognito_exec(
        "INSERT INTO user_profile (username, sub, provider, provider_user_id, identities, attributes) "
        "VALUES (%s, %s, %s, %s, %s, %s)",
        (username, sub, PROVIDER, name_id.text, json.dumps(identities), json.dumps(attributes)))
    # Stand-in for an administrator's AdminAddUserToGroup after first sign-in.
    login = attributes.get("custom:login")
    if login:
        db.cognito_exec(
            "INSERT INTO user_group (username, group_name) "
            "SELECT %s, group_name FROM group_seed WHERE login = %s ON CONFLICT DO NOTHING",
            (username, login))
    return profile_by_username(username)


@app.route("/oauth2/token", methods=["POST", "OPTIONS"])
def token():
    if request.method == "OPTIONS":
        return cors(Response(status=204))
    f = request.form
    if f.get("client_id") != CLIENT_ID:
        return cors(jsonify(error="invalid_client")), 400
    if f.get("grant_type") == "authorization_code":
        row = db.cognito_one(
            "UPDATE auth_code SET used = true WHERE code_hash = %s AND NOT used AND expires_at > now() RETURNING *",
            (hashlib.sha256(f.get("code", "").encode()).hexdigest(),))
        if not row or row["redirect_uri"] != f.get("redirect_uri"):
            return cors(jsonify(error="invalid_grant")), 400
        challenge = b64url(hashlib.sha256(f.get("code_verifier", "").encode()).digest())
        if challenge != row["code_challenge"]:
            return cors(jsonify(error="invalid_grant")), 400
        return cors(jsonify(issue_tokens(profile_by_username(row["username"]), row["auth_time"])))
    if f.get("grant_type") == "refresh_token":
        result = refresh(f.get("refresh_token", ""))
        if result is None:
            return cors(jsonify(error="invalid_grant")), 400
        return cors(jsonify(result))
    return cors(jsonify(error="unsupported_grant_type")), 400


def refresh(refresh_token):
    row = db.cognito_one(
        "SELECT * FROM refresh_token WHERE token_hash = %s AND revoked_at IS NULL AND expires_at > now()",
        (hashlib.sha256(refresh_token.encode()).hexdigest(),))
    if not row:
        return None
    profile = profile_by_username(row["username"])
    if not profile or not profile["enabled"]:
        return None
    return issue_tokens(profile, row["auth_time"], with_refresh=False)


@app.route("/oauth2/revoke", methods=["POST", "OPTIONS"])
def revoke():
    if request.method == "OPTIONS":
        return cors(Response(status=204))
    db.cognito_exec("UPDATE refresh_token SET revoked_at = now() WHERE token_hash = %s AND revoked_at IS NULL",
                    (hashlib.sha256(request.form.get("token", "").encode()).hexdigest(),))
    return cors(Response(status=200))


def cors(response):
    response.headers["Access-Control-Allow-Origin"] = UI_ORIGIN
    response.headers["Access-Control-Allow-Methods"] = "POST, OPTIONS"
    response.headers["Access-Control-Allow-Headers"] = (
        "content-type, x-amz-target, x-amz-user-agent, cache-control, authorization")
    response.headers["Vary"] = "Origin"
    return response


# --------------------------------------------- user-pool API (Amplify)

@app.route("/", methods=["POST", "OPTIONS"])
def user_pool_api():
    if request.method == "OPTIONS":
        return cors(Response(status=204))
    target = request.headers.get("X-Amz-Target", "").removeprefix("AWSCognitoIdentityProviderService.")
    body = request.get_json(force=True, silent=True) or {}

    def error(kind, message, status=400):
        return cors(Response(json.dumps({"__type": kind, "message": message}), status=status,
                             mimetype="application/x-amz-json-1.1"))

    def ok(payload):
        return cors(Response(json.dumps(payload), mimetype="application/x-amz-json-1.1"))

    if target in ("GetTokensFromRefreshToken", "InitiateAuth"):
        rt = body.get("RefreshToken") or (body.get("AuthParameters") or {}).get("REFRESH_TOKEN", "")
        result = refresh(rt)
        if result is None:
            return error("NotAuthorizedException", "Refresh Token has been revoked")
        return ok({"AuthenticationResult": {"AccessToken": result["access_token"], "IdToken": result["id_token"],
                                            "ExpiresIn": ACCESS_TTL, "TokenType": "Bearer"},
                   "ChallengeParameters": {}})
    if target == "GlobalSignOut":
        claims = verify_own_access_token(body.get("AccessToken", ""))
        if not claims:
            return error("NotAuthorizedException", "Invalid Access Token")
        db.cognito_exec("UPDATE refresh_token SET revoked_at = now() WHERE username = %s AND revoked_at IS NULL",
                        (claims["username"],))
        db.event("GLOBAL_SIGN_OUT", claims["username"], {})
        return ok({})
    if target == "RevokeToken":
        db.cognito_exec("UPDATE refresh_token SET revoked_at = now() WHERE token_hash = %s",
                        (hashlib.sha256(body.get("Token", "").encode()).hexdigest(),))
        return ok({})
    return error("NotImplementedException", f"{target} is not simulated in the lab", 400)


# ------------------------------------------------------------ logout

@app.get("/logout")
def logout():
    if request.args.get("client_id") != CLIENT_ID or request.args.get("logout_uri") not in LOGOUT_URIS:
        return lab_page("Invalid logout", "<p>Unknown client or unregistered logout_uri.</p>", 400)
    back = request.args["logout_uri"]
    session = db.cognito_one(
        "UPDATE saml_session SET ended_at = now() WHERE session_id = %s AND ended_at IS NULL RETURNING *",
        (request.cookies.get(SESSION_COOKIE, ""),))
    if not session:
        out = redirect(back)
    else:
        name_id = NameID(text=session["name_id"], format=session["name_id_format"],
                         name_qualifier=session["name_qualifier"], sp_name_qualifier=session["sp_name_qualifier"])
        req_id, req = SAML.create_logout_request(
            IDP_SLO, IDP_ENTITY, name_id=name_id, session_indexes=[session["session_index"]], sign=False)
        relay = secrets.token_urlsafe(24)
        db.cognito_exec("INSERT INTO pending_logout (relay_state, saml_request_id, logout_uri) VALUES (%s, %s, %s)",
                        (relay, req_id, back))
        info = SAML.apply_binding(BINDING_HTTP_REDIRECT, str(req), IDP_SLO, relay, sign=True,
                                  sigalg="http://www.w3.org/2001/04/xmldsig-more#rsa-sha256")
        db.event("SAML_LOGOUT_REQUESTED", session["username"], {})
        out = redirect(dict(info["headers"])["Location"])
    out.delete_cookie(SESSION_COOKIE)
    return out


@app.get("/saml2/logout")
def saml_logout_response():
    pending = db.cognito_one("DELETE FROM pending_logout WHERE relay_state = %s RETURNING *",
                             (request.args.get("RelayState", ""),))
    if not pending or "SAMLResponse" not in request.args:
        return lab_page("Logout", "<p>Unknown logout attempt.</p>", 400)
    try:
        response = SAML.parse_logout_request_response(request.args["SAMLResponse"], BINDING_HTTP_REDIRECT)
        status = response.response.status.status_code.value if response else "none"
    except Exception as e:
        status = f"rejected:{type(e).__name__}"
    db.event("SAML_LOGOUT_COMPLETED", None, {"status": status})
    return redirect(pending["logout_uri"])


@app.get("/lab/health")
def health():
    return jsonify(status="ok", label="Local SAML integration - Cognito simulated")
