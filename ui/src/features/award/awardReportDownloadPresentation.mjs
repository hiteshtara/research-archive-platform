// Award report download: endpoint paths and button presentation.
//
// This lives in a plain module rather than inline in client.ts/the page
// because this repo has no component-render test setup (see CLAUDE.md),
// and the thing most worth pinning is that each action calls the RIGHT
// endpoint. A report that silently downloaded the wrong variant would
// look completely normal to the user.

/** Report only - the original, unchanged action. */
export const REPORT_ONLY = "REPORT_ONLY";

/** Report plus this Award VERSION's archived PDF attachments. */
export const REPORT_WITH_ATTACHMENTS = "REPORT_WITH_ATTACHMENTS";

/**
 * API path for a report download.
 *
 * The two variants are separate endpoints, not a query parameter, so the
 * lightweight report keeps its own contract and the expensive one is
 * visible in access logs and metrics.
 */
export function buildAwardReportPath(awardId, kind = REPORT_ONLY) {
  const id = encodeURIComponent(awardId);
  return kind === REPORT_WITH_ATTACHMENTS
    ? `/api/v1/awards/${id}/report-with-attachments.pdf`
    : `/api/v1/awards/${id}/report.pdf`;
}

/**
 * Button label/state.
 *
 * Deliberately says "this Award version" and never "all attachments":
 * the endpoint is version-scoped (award_id), not family-scoped. Award
 * 105698-00001 has 9 attachments on its current version and 81 across
 * its 20 versions, so the looser wording would be wrong.
 */
export function resolveReportActionState(kind, downloading) {
  const withAttachments = kind === REPORT_WITH_ATTACHMENTS;
  const idleLabel = withAttachments
    ? "Download Report + Attachments"
    : "Download Award Report";
  return {
    label: downloading ? "Preparing report…" : idleLabel,
    idleLabel,
    disabled: Boolean(downloading),
    busy: Boolean(downloading),
    tooltip: withAttachments
      ? "Downloads the Award report with archived PDF attachments for "
        + "this Award version."
      : "Downloads the generated Award report only.",
  };
}

/** Fallback filename; the API's Content-Disposition always wins. */
export function buildAwardReportFallbackFileName(awardNumber, kind = REPORT_ONLY) {
  const safe = String(awardNumber ?? "award").replace(/[^A-Za-z0-9._-]/g, "_");
  return kind === REPORT_WITH_ATTACHMENTS
    ? `Award_${safe}_Complete_Report_With_Attachments.pdf`
    : `Award_${safe}_Complete_Report.pdf`;
}

/** User-facing message for a failed download. Never mentions storage. */
export function reportDownloadErrorMessage(status, kind = REPORT_ONLY) {
  if (status === 404) {
    return "This Award's report could not be generated.";
  }
  const what = kind === REPORT_WITH_ATTACHMENTS
    ? "report with attachments"
    : "report";
  return `Download failed with status ${status}. The Award ${what} was not downloaded.`;
}
