export function originOf(value: string | undefined | null): string | null;

export function parseAllowedOrigins(raw: string | undefined | null): string[];

export function resolveOauthRedirects(input: {
  currentOrigin?: string | null;
  allowedOriginsRaw?: string | null;
  configuredSignInUrl: string;
  configuredSignOutUrl: string;
}): {
  signIn: string;
  signOut: string;
  allowedOrigins: string[];
  originAware: boolean;
};
