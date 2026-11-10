import Alert from "@mui/material/Alert";
import AlertTitle from "@mui/material/AlertTitle";
import Typography from "@mui/material/Typography";

import {
  accessNotice,
  supportReferenceFor,
} from "./accessStatePresentation.mjs";
import type { AccessNoticeContent } from "./accessStatePresentation.d.mts";

/**
 * The notice a user sees instead of an empty screen.
 *
 * <p>The case this exists for: somebody signs in, has no grants, and sees a
 * Dashboard reading zero. That is indistinguishable from a broken deployment,
 * and "the archive is down" is the report that follows. The API has always
 * said which of the two it is; nothing rendered it.
 *
 * <p>For a 404 this renders "not found" and no more. An out-of-scope record
 * and a record that never existed answer identically from the API on purpose,
 * and a sentence like "or you may not have permission" would hand back the one
 * bit the 404 withholds.
 */
export function AccessNotice({
  error,
  recordLabel,
}: {
  error: { status?: number; code?: string; correlationId?: string } | null;
  recordLabel?: string;
}) {
  if (!error) {
    return null;
  }
  const notice = accessNotice(error, { recordLabel }) as AccessNoticeContent;
  const reference = supportReferenceFor(error);

  return (
    <Alert severity={notice.severity} sx={{ my: 2 }}>
      <AlertTitle>{notice.title}</AlertTitle>
      <Typography variant="body2">{notice.body}</Typography>
      {notice.action ? (
        <Typography variant="body2" sx={{ mt: 1 }}>
          {notice.action}
        </Typography>
      ) : null}
      {reference ? (
        <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: "block" }}>
          Reference: {reference}
        </Typography>
      ) : null}
    </Alert>
  );
}
