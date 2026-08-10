// The pure part of LoadingState's "skeleton" mode: turning
// height/count/heights props into the concrete list of skeleton row
// heights to render. Extracted so the three ways a section can ask for
// skeleton rows (one row via `height`, several identical rows via
// `count`, or mixed-height rows via `heights`) are each covered by a
// real test rather than only exercised indirectly through JSX.
export function resolveSkeletonRowHeights({ height = 220, count = 1, heights } = {}) {
  return heights ?? Array.from({ length: count }, () => height);
}
