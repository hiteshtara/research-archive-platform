import type { AwardBudgetSummaryV1, AwardBudgetVersionV1 } from "../../types/api";

export function budgetScopeNote(summary: AwardBudgetSummaryV1 | null | undefined): string | null;
export function selectedBudgetLabel(summary: AwardBudgetSummaryV1 | null | undefined): string;
export function owningSequenceLabel(version: AwardBudgetVersionV1 | null | undefined): string;
export function hasAnyBudgetVersions(versions: unknown[]): boolean;
export function personnelEmptyStateMessage(): string;
