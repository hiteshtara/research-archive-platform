export const REASON_MIN_LENGTH: number;
export const REASON_MAX_LENGTH: number;
export const PLACEHOLDER_PHRASES: string[];

export function normalizeReason(raw: string | null | undefined): string;

export function isPlaceholderReason(raw: string | null | undefined): boolean;

export function reasonProblem(raw: string | null | undefined): string | null;

export function isValidReason(raw: string | null | undefined): boolean;
