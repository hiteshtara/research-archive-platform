export const REAUTHENTICATION_REQUIRED: string;
export const REAUTHENTICATION_COOLDOWN_MS: number;
export const REAUTHENTICATION_MESSAGE: string;
export function isReauthenticationRequired(status: number, code: string | undefined): boolean;
export function shouldRedirectToSignIn(lastAttemptMs: number | null, nowMs: number): boolean;
