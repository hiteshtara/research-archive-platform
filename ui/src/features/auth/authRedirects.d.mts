export const APPROVED_IDP_LOGOUT_URLS: string[];

export function originOf(value: string | undefined | null): string | null;

export function parseAllowedOrigins(raw: string | undefined | null): string[];

export function resolveOauthRedirects(input: {
  currentOrigin?: string | null;
  allowedOriginsRaw?: string | null;
  configuredSignInUrl: string;
  configuredSignOutUrl: string;
  /** Post-logout path applied to our own origins only. Defaults to "/". */
  signOutPath?: string;
  /**
   * Identity-provider logout endpoints that may be used as the sign-out
   * target. Defaults to {@link APPROVED_IDP_LOGOUT_URLS}; anything not on it
   * degrades to the own-origin post-logout page.
   */
  approvedIdpLogoutUrls?: string[];
}): {
  signIn: string;
  signOut: string;
  allowedOrigins: string[];
  originAware: boolean;
  /**
   * True when `signOut` is a reviewed identity-provider logout endpoint.
   * Names which endpoint is being used, not what the IdP does with it.
   */
  signOutUsesIdpLogout: boolean;
};
