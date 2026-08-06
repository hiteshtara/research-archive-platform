import type { SubawardContact } from "../../types/api";

export interface ContactDisplay {
  name: string | null;
  role: string | null;
  organization: string | null;
  phone: string | null;
  email: string | null;
  hasIdentity: boolean;
}

export function resolveContactDisplay(contact: SubawardContact): ContactDisplay;
