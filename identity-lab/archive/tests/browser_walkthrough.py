"""Browser walkthrough of the running local SAML lab (FICTIONAL accounts).

For each of PI, Department, Central and IO: a fresh Chromium profile opens the
archive UI, is sent by the production Amplify sign-in to the simulated Cognito,
signs in on the real Shibboleth IdP login page, returns to the archive, and
the script records what the archive actually shows - the search results and a
direct URL to a record outside the user's scope. The PI run also signs out and
checks that the next visit asks for the password again. Every request host is
recorded; the run fails if any request leaves the machine (e.g. to AWS).

Usage: uv run --no-project --with playwright python browser_walkthrough.py [screenshot-dir]
The browser context accepts the lab's TEST certificates; nothing is installed
into the operating system's trust store.
"""
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright

UI = "http://localhost:5198"
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "walkthrough")
OUT.mkdir(parents=True, exist_ok=True)
AWARD = re.compile(r"\b9900\d\d-0000\d\b")

USERS = [
    # login, password, expected families, a direct URL that must be refused
    ("lab-pat", "Lab-Pat-2026", {"990001-00001", "990003-00001", "990004-00001", "990009-00001"}, "/awards/9000201"),
    ("lab-dept", "Lab-Dept-2026", {"990001-00001", "990001-00002", "990009-00001"}, "/awards/9000201"),
    ("lab-central", "Lab-Central-2026", {"990001-00001", "990001-00002", "990002-00001", "990003-00001",
                                         "990004-00001", "990005-00001", "990006-00001", "990007-00001",
                                         "990008-00001", "990009-00001", "990010-00001"}, None),
    ("lab-io", "Lab-Io-2026", {"990006-00001", "990009-00001"}, "/awards/9000801"),
    ("lab-kim-pi", "Lab-Kim-Pi-2026", {"990010-00001"}, "/awards/9000102"),
]
LOCAL_HOSTS = {"localhost", "127.0.0.1"}


def run():
    report = {"users": [], "hosts": set()}
    with sync_playwright() as p:
        browser = p.chromium.launch()
        for login, password, expected, forbidden in USERS:
            context = browser.new_context(ignore_https_errors=True, viewport={"width": 1280, "height": 900})
            page = context.new_page()
            page.on("request", lambda r: report["hosts"].add(urlparse(r.url).hostname))
            entry = {"login": login}

            page.goto(f"{UI}/awards/search?q=SYNTHETIC")
            page.wait_for_selector("#username", timeout=60_000)          # real IdP login page
            entry["idp_login_page"] = page.url.split("?")[0]
            if login == "lab-pat":
                page.screenshot(path=OUT / "01-idp-login-page.png")
            page.fill("#username", login)
            page.fill("#password", password)
            page.click("button[name=_eventId_proceed]")

            # Amplify exchanges the code, then the app calls the archive API.
            with page.expect_response(lambda r: "/api/v1/me/access" in r.url, timeout=60_000):
                page.wait_for_url(f"{UI}/**", timeout=60_000)
            page.wait_for_selector("[data-testid=identity-lab-banner]", timeout=60_000)
            page.wait_for_timeout(1000)
            page.goto(f"{UI}/awards/search?q=SYNTHETIC")
            page.wait_for_function(
                "() => /9900\\d\\d-0000\\d/.test(document.body.innerText) || /No (results|awards)/i.test(document.body.innerText)",
                timeout=60_000)
            page.wait_for_timeout(1500)
            banner = page.inner_text("[data-testid=identity-lab-banner]")
            shown = set(AWARD.findall(page.inner_text("main") if page.query_selector("main") else page.inner_text("body")))
            entry.update({"banner": banner, "families_shown": sorted(shown), "matches_expected": shown == expected})
            page.screenshot(path=OUT / f"02-{login}-search.png", full_page=True)

            if forbidden:
                page.goto(f"{UI}{forbidden}")
                page.wait_for_timeout(4000)
                body = page.inner_text("body")
                entry["forbidden_url"] = forbidden
                entry["forbidden_shows_record"] = bool(re.search(r"SYNTHETIC Award (B|H)\b", body))
                entry["forbidden_page_excerpt"] = " ".join(body.split())[:160]
                page.screenshot(path=OUT / f"03-{login}-direct-url-refused.png", full_page=True)

            if login == "lab-pat":
                # No ArchiveAttachmentViewer group: record access covers the record's own files
                # (approved 2026-10-01); another record's file is refused even by direct URL.
                files = page.evaluate("""async () => {
                    const k = Object.keys(localStorage).find(k => k.endsWith('.accessToken'));
                    const H = {Authorization: 'Bearer ' + localStorage.getItem(k)};
                    const out = {};
                    for (const [name, url] of [['own', '/api/v1/awards/9000102/attachments/9300001/download'],
                                               ['other', '/api/v1/awards/9000201/attachments/9300002/download'],
                                               ['otherViaOwnUrl', '/api/v1/awards/9000102/attachments/9300002/download'],
                                               ['report', '/api/v1/awards/9000102/report-with-attachments.pdf']]) {
                        const r = await fetch('http://localhost:8092' + url, {headers: H});
                        const b = new Uint8Array(await r.arrayBuffer());
                        out[name] = {status: r.status, pdf: String.fromCharCode(...b.slice(0, 4)) === '%PDF', bytes: b.length};
                    }
                    return out;
                }""")
                entry["files_without_attachment_group"] = files
                entry["files_ok"] = (files["own"]["status"] == 200 and files["own"]["pdf"]
                                     and files["report"]["status"] == 200 and files["report"]["pdf"]
                                     and files["other"]["status"] == 404 and not files["other"]["pdf"]
                                     and files["otherViaOwnUrl"]["status"] == 404)
                page.goto(f"{UI}/awards/9000102")
                page.wait_for_timeout(3000)
                page.screenshot(path=OUT / "05-lab-pat-award-A-files-no-group.png", full_page=True)
                page.goto(f"{UI}/awards/search?q=SYNTHETIC")
                page.wait_for_selector("[data-testid=identity-lab-logout]")
                page.click("[data-testid=identity-lab-logout]")
                page.wait_for_selector("#username", timeout=60_000)       # IdP asks for the password again
                entry["after_logout"] = page.url.split("?")[0]
                page.screenshot(path=OUT / "04-after-logout-idp-asks-again.png")
            report["users"].append(entry)
            context.close()
        browser.close()
    report["hosts"] = sorted(h for h in report["hosts"] if h)
    report["only_local_hosts"] = set(report["hosts"]) <= LOCAL_HOSTS
    (OUT / "walkthrough.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
    ok = report["only_local_hosts"] and all(
        u["matches_expected"] and not u.get("forbidden_shows_record", False) and u.get("files_ok", True)
        for u in report["users"])
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    run()
