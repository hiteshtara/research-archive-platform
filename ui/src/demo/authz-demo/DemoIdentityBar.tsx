/*
 * SYNTHETIC IDENTITY DEMO - BU FEDERATION NOT CONNECTED.
 * Loaded only in Vite mode "authz-demo" (see main.tsx). Lets a reviewer
 * switch between fictional personas. Switching reloads the page, so no
 * cached data from one persona is ever shown to another.
 */
import { useEffect, useState } from "react";

import { DEMO_PERSONA_STORAGE_KEY, currentPersona } from "./demoAuth";

interface Persona {
  key: string;
  label: string;
  attachmentViewer: boolean;
  description: string;
}

interface AccessStatus {
  mode: string;
  label: string;
  problem: string | null;
  grantKinds: string[];
}

const API = import.meta.env.VITE_API_BASE_URL;

export function DemoIdentityBar() {
  const [personas, setPersonas] = useState<Persona[]>([]);
  const [status, setStatus] = useState<AccessStatus | null>(null);
  const selected = currentPersona();

  useEffect(() => {
    fetch(`${API}/demo/personas`).then((r) => r.json()).then(setPersonas).catch(() => setPersonas([]));
    fetch(`${API}/api/v1/me/access`, { headers: { Authorization: `Bearer demo-persona:${selected}` } })
      .then((r) => r.json())
      .then(setStatus)
      .catch(() => setStatus(null));
  }, [selected]);

  const persona = personas.find((p) => p.key === selected);

  function choose(key: string) {
    try {
      window.localStorage.setItem(DEMO_PERSONA_STORAGE_KEY, key);
    } catch {
      // ignore
    }
    window.location.assign("/");
  }

  return (
    <div
      role="region"
      aria-label="Synthetic identity demo"
      data-testid="authz-demo-bar"
      style={{
        position: "fixed", left: 0, right: 0, bottom: 0, zIndex: 2000,
        background: "#3a2a00", color: "#fff", padding: "8px 16px",
        display: "flex", flexWrap: "wrap", gap: "12px", alignItems: "center",
        fontFamily: "system-ui, sans-serif", fontSize: 14, borderTop: "3px solid #f2b705",
      }}
    >
      <strong>Synthetic identity demo—BU federation not connected</strong>
      <label>
        Test user:{" "}
        <select
          data-testid="authz-demo-persona"
          value={selected}
          onChange={(e) => choose(e.target.value)}
          style={{ fontSize: 14 }}
        >
          {personas.length === 0 && <option value={selected}>{selected}</option>}
          {personas.map((p) => (
            <option key={p.key} value={p.key}>{p.label}</option>
          ))}
        </select>
      </label>
      {persona && <span style={{ opacity: 0.85 }}>{persona.description}</span>}
      <span data-testid="authz-demo-status" style={{ marginLeft: "auto" }}>
        {status
          ? status.problem
            ? `No access: ${status.problem}`
            : `Access: ${status.grantKinds.join(" + ") || "none"} (${status.label})`
          : "Access: unknown"}
        {persona ? (persona.attachmentViewer ? " · attachment group: yes" : " · attachment group: no") : ""}
      </span>
    </div>
  );
}
