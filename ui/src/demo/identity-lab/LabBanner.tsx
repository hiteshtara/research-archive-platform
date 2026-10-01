/*
 * LOCAL SAML INTEGRATION - COGNITO SIMULATED.
 * Loaded only in Vite mode "identity-lab" (see main.tsx). Shows who signed in
 * through the lab's Shibboleth IdP and simulated Cognito, and what the archive
 * API says about their access. A successful login is NOT complete
 * authorization: the unfinished paths stay listed here.
 */
import { useEffect, useState } from "react";
import { fetchAuthSession } from "aws-amplify/auth";

import { accessToken, logout } from "../../auth";

interface AccessStatus {
  mode: string;
  label: string;
  problem: string | null;
  grantKinds: string[];
}

const API = import.meta.env.VITE_API_BASE_URL;

export const UNFINISHED_PATHS =
  "Negotiation, Subaward, IRB, Document Explorer and the legacy Award history routes are closed for " +
  "non-Central users; AI is available only when every version of the Award is visible.";

export function LabBanner() {
  const [login, setLogin] = useState<string>("…");
  const [status, setStatus] = useState<AccessStatus | null>(null);
  const [statusCode, setStatusCode] = useState<number | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        const session = await fetchAuthSession();
        const id = session.tokens?.idToken?.payload;
        setLogin(String(id?.["custom:login"] ?? session.tokens?.accessToken?.payload?.username ?? "unknown"));
      } catch {
        setLogin("not signed in");
      }
      const token = await accessToken();
      if (!token) {
        return;
      }
      const response = await fetch(`${API}/api/v1/me/access`, { headers: { Authorization: `Bearer ${token}` } });
      setStatusCode(response.status);
      setStatus(await response.json().catch(() => null));
    })();
  }, []);

  const access = status
    ? status.problem
      ? `No access: ${status.problem}`
      : `Access: ${status.grantKinds.join(" + ") || "none"}`
    : statusCode
      ? `Access check HTTP ${statusCode}`
      : "Access: checking…";

  return (
    <div
      role="region"
      aria-label="Local SAML integration, Cognito simulated"
      data-testid="identity-lab-banner"
      style={{
        position: "fixed", left: 0, right: 0, bottom: 0, zIndex: 2000,
        background: "#14324f", color: "#fff", padding: "8px 16px",
        display: "flex", flexWrap: "wrap", gap: "12px", alignItems: "center",
        fontFamily: "system-ui, sans-serif", fontSize: 14, borderTop: "3px solid #5bc0eb",
      }}
    >
      <strong>Local SAML integration—Cognito simulated</strong>
      <span data-testid="identity-lab-login">Signed in via lab Shibboleth as: {login}</span>
      <span data-testid="identity-lab-access">{access}</span>
      <span style={{ opacity: 0.8, flexBasis: "100%" }}>
        Login is not complete authorization. Unfinished: {UNFINISHED_PATHS}
      </span>
      <button
        type="button"
        data-testid="identity-lab-logout"
        onClick={() => void logout()}
        style={{ marginLeft: "auto", fontSize: 14 }}
      >
        Sign out
      </button>
    </div>
  );
}
