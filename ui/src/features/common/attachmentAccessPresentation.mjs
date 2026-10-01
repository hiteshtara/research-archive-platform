// Whether the UI should offer attachment, file and "report with attachments" controls.
//
// Mirrors the server (AttachmentAuthorizationService), never replaces it:
// - record authorization ENFORCED (approved 2026-10-01): a user may see a record's own files
//   whenever they may see the record; the server checks every record and file request, so the
//   controls are offered and a refused request still fails. No ArchiveAttachmentViewer needed.
// - enforcement OFF (today's deployed behaviour): the ArchiveAttachmentViewer group is required.
// - an identity the server reports as not provisioned or denied: no controls.
// Anything unexpected fails closed (no controls).

export const ATTACHMENT_VIEWER_GROUP = "ArchiveAttachmentViewer";

export function attachmentControlsAvailable(accessStatus, groups) {
  if (accessStatus && accessStatus.mode === "ENFORCED") {
    return accessStatus.problem === null || accessStatus.problem === undefined;
  }
  return Array.isArray(groups) && groups.includes(ATTACHMENT_VIEWER_GROUP);
}
