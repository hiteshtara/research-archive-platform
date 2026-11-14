import Alert from "@mui/material/Alert";
import AlertTitle from "@mui/material/AlertTitle";

import { readOnlyNotice } from "./securityAdminPresentation.mjs";
import { useCapabilities } from "./useCapabilities";

/*
 * "Read-Only Mode", stated once at the top of every administration screen.
 *
 * Shown whenever the server reports administrationWritesAvailable: false.
 * It renders nothing when writes are permitted, and nothing when
 * administration is unavailable altogether - that case already has its own
 * full-screen explanation, and two notices would be noise.
 *
 * This is presentation. The server refuses every write with 409
 * ADMINISTRATION_WRITES_DISABLED regardless of what is rendered; the point
 * of the banner is that an administrator should not have to discover the
 * configuration by being refused.
 */
export function ReadOnlyBanner() {
  const capabilities = useCapabilities();
  const notice = readOnlyNotice(capabilities);

  if (!notice) {
    return null;
  }

  return (
    <Alert
      severity="info"
      variant="outlined"
      data-testid="read-only-banner"
      sx={{ borderRadius: 2 }}
    >
      <AlertTitle sx={{ fontWeight: 700, mb: 0.25 }}>Read-Only Mode</AlertTitle>
      {notice.detail}
    </Alert>
  );
}

