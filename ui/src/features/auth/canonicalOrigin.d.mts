export const SENSITIVE_AUTH_PARAMS: string[];

export function isLoopbackHostname(hostname: string): boolean;

export function resolveCanonicalRedirect(input: {
  canonicalOrigin?: string | null;
  currentHref?: string | null;
}): string | null;
