export type StatusVariant = "success" | "warning" | "default" | "error" | "neutral";

export function resolveAwardStyleStatusVariant(
  status: string | null,
): StatusVariant;

export function resolveNegotiationStatusVariant(
  status: string | null,
): StatusVariant;

export function resolveSubawardStatusVariant(
  status: string | null,
): StatusVariant;

export function resolveStatusVariant(
  domain: string,
  status: string | null,
): StatusVariant;
