/*
 * SYNTHETIC IDENTITY DEMO - BU FEDERATION NOT CONNECTED.
 * Replaces src/auth.ts ONLY in Vite mode "authz-demo" (see vite.config.ts).
 * There is no sign-in: the selected fictional persona is sent as
 * "Bearer demo-persona:KEY", which only the -Pauthz-demo API build accepts.
 */
export const DEMO_PERSONA_STORAGE_KEY = "authzDemoPersona";
const DEFAULT_PERSONA = "pi";

export function currentPersona(): string {
  try {
    return window.localStorage.getItem(DEMO_PERSONA_STORAGE_KEY) || DEFAULT_PERSONA;
  } catch {
    return DEFAULT_PERSONA;
  }
}

export async function login(): Promise<void> {}

export async function logout(): Promise<void> {
  try {
    window.localStorage.removeItem(DEMO_PERSONA_STORAGE_KEY);
  } catch {
    // ignore
  }
  window.location.assign("/");
}

export async function currentUser() {
  return { username: `demo persona: ${currentPersona()}`, userId: `demo-${currentPersona()}` };
}

export async function accessToken(): Promise<string | null> {
  return `demo-persona:${currentPersona()}`;
}

export async function hasAttachmentAccess(): Promise<boolean> {
  try {
    const response = await fetch(`${import.meta.env.VITE_API_BASE_URL}/demo/personas`);
    const personas: { key: string; attachmentViewer: boolean }[] = await response.json();
    return personas.some((p) => p.key === currentPersona() && p.attachmentViewer);
  } catch {
    return false;
  }
}
