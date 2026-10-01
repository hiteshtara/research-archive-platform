import assert from "node:assert/strict";
import test from "node:test";

import { attachmentControlsAvailable } from "./attachmentAccessPresentation.mjs";

test("enforced and provisioned: files are offered without the attachment group", () => {
  assert.equal(attachmentControlsAvailable({ mode: "ENFORCED", problem: null }, []), true);
  assert.equal(attachmentControlsAvailable({ mode: "ENFORCED", problem: null }, undefined), true);
});

test("enforced but not provisioned or denied: no file controls", () => {
  assert.equal(attachmentControlsAvailable({ mode: "ENFORCED", problem: "ACCESS_NOT_PROVISIONED" }, ["ArchiveAttachmentViewer"]), false);
  assert.equal(attachmentControlsAvailable({ mode: "ENFORCED", problem: "ACCESS_DENIED" }, []), false);
});

test("enforcement off: today's rule, the ArchiveAttachmentViewer group is required", () => {
  assert.equal(attachmentControlsAvailable({ mode: "NOT_ENFORCED", problem: null }, ["ArchiveAttachmentViewer"]), true);
  assert.equal(attachmentControlsAvailable({ mode: "NOT_ENFORCED", problem: null }, []), false);
  assert.equal(attachmentControlsAvailable(null, ["ArchiveAttachmentViewer"]), true);
  assert.equal(attachmentControlsAvailable(null, "ArchiveAttachmentViewer"), false);
});
