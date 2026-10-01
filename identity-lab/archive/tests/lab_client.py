"""Drives a real browser-less SAML login through the lab: simulated Cognito
/oauth2/authorize -> Shibboleth IdP password form -> SAML POST -> code -> tokens.
FICTIONAL accounts only."""
import base64
import hashlib
import secrets
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import parse_qs, urljoin, urlparse

import requests

ROOT = Path(__file__).resolve().parents[3]
CA = str(ROOT / ".identity-lab" / "creds" / "ca.crt")
COGNITO = "https://localhost:9443"
API = "http://127.0.0.1:8092"
CLIENT_ID = "labsimulatedclient"
REDIRECT = "http://localhost:5198/"


class _Forms(HTMLParser):
    def __init__(self):
        super().__init__()
        self.forms = []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "form":
            self.forms.append({"action": a.get("action", ""), "inputs": {}})
        elif tag in ("input", "button") and self.forms and a.get("name"):
            self.forms[-1]["inputs"][a["name"]] = a.get("value", "")


def forms(html):
    p = _Forms()
    p.feed(html)
    return p.forms


class SignInFailed(Exception):
    def __init__(self, page):
        super().__init__(page[:300])
        self.page = page


def b64url(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def login(username, password, session=None):
    """Returns (tokens, browser_session). Raises SignInFailed when no code is issued."""
    s = session or requests.Session()
    s.verify = CA
    verifier = secrets.token_urlsafe(48)
    state = secrets.token_urlsafe(8)
    r = s.get(f"{COGNITO}/oauth2/authorize", params={
        "client_id": CLIENT_ID, "redirect_uri": REDIRECT, "response_type": "code",
        "scope": "openid email profile", "state": state,
        "code_challenge": b64url(hashlib.sha256(verifier.encode()).digest()),
        "code_challenge_method": "S256"})
    for _ in range(6):
        page = forms(r.text)
        if not page:
            raise SignInFailed(r.text)
        form = page[0]
        target = urljoin(r.url, form["action"])
        data = dict(form["inputs"])
        if "j_username" in data:
            data.update({"j_username": username, "j_password": password, "_eventId_proceed": ""})
            r = s.post(target, data=data)
            continue
        if "SAMLResponse" in data:
            r = s.post(target, data=data, allow_redirects=False)
            if r.status_code != 302 or not r.headers["Location"].startswith(REDIRECT):
                raise SignInFailed(r.text)
            q = parse_qs(urlparse(r.headers["Location"]).query)
            assert q["state"] == [state]
            t = s.post(f"{COGNITO}/oauth2/token", data={
                "grant_type": "authorization_code", "client_id": CLIENT_ID, "code": q["code"][0],
                "redirect_uri": REDIRECT, "code_verifier": verifier})
            t.raise_for_status()
            return t.json(), s
        data.setdefault("_eventId_proceed", "")
        r = s.post(target, data=data)
    raise SignInFailed(r.text)


def api(token, path):
    return requests.get(f"{API}{path}", headers={"Authorization": f"Bearer {token}"}, timeout=30)
