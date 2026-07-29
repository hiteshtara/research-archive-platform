import type {
  AwardAiCurrentRecord,
  AwardAiTimelineRecord,
} from "../../types/api";

export function orderAwardTimeline(
  timeline: AwardAiTimelineRecord[],
): AwardAiTimelineRecord[];

export function timelineLabel(
  sequenceNumber: number,
  currentSequence: number,
  earliestSequence: number,
): "Current" | "Previous" | "Original";

export function showDevelopmentMetadata(isDevelopment: boolean): boolean;

export function estimatedReadingSeconds(textParts: string[]): number;

export function sequenceLabelFromChange(change: string): string | null;

export function currentAwardFacts(
  currentRecord: AwardAiCurrentRecord,
  formatAmount: (amount: number) => string,
): Array<{ label: string; value: string }>;

export function visibleAwardTimeline(
  orderedTimeline: AwardAiTimelineRecord[],
  expanded: boolean,
  recentCount?: number,
): AwardAiTimelineRecord[];
