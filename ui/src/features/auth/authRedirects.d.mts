export function originOf(value: string | undefined | null): string | null;

export function parseAllowedOrigins(raw: string | undefined | null): string[];

export function resolveOauthRedirects(input: {
  currentOrigin?: string | null;
  allowedOriginsRaw?: string | null;
  configuredSignInUrl: string;
  configuredSignOutUrl: string;
  /** Post-logout path applied to our own origins only. Defaults to "/". */
  signOutPath?: string;
}): {
  signIn: string;
  signOut: string;
  allowedOrigins: string[];
  originAware: boolean;
  /**
   * True when `signOut` points somewhere that is not one of our own origins -
   * an identity provider's logout page, which ends the IdP session.
   */
  signOutEndsIdpSession: boolean;
};
