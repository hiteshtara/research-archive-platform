import type {
  TimeAndMoneyHistoryEntryV1,
  TimeAndMoneySummaryV1,
} from "../../types/api";

export function describeHistoryEntry(
  entry: TimeAndMoneyHistoryEntryV1 | null | undefined,
): {
  timeAndMoneyCreated: boolean;
  versionsAgree: boolean;
  versionNote: string | null;
};

export function describeLastAction(
  summary: TimeAndMoneySummaryV1 | null | undefined,
): string;

export function fandaDistributionPeriodLabel(
  period: string | null | undefined,
): string;
