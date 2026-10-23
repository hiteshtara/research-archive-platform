export const SIGNED_OUT_MARKER: string;

export function markSignedOut(storage: Pick<Storage, "setItem">): void;

export function clearSignedOutMarker(storage: Pick<Storage, "removeItem">): void;

export function wasSignedOut(storage: Pick<Storage, "getItem">): boolean;
