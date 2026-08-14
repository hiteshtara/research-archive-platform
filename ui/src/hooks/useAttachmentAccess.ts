import { useEffect, useState } from "react";

import { hasAttachmentAccess } from "../auth";

// Frontend convenience only - see auth.ts's hasAttachmentAccess. The
// real boundary is AttachmentAuthorizationService server-side; every
// attachment endpoint re-checks the real Cognito group on every
// request regardless of what this hook returns. Starts false (fail
// closed) - attachment UI only appears once the group check actually
// resolves true, never optimistically. Shared by every page/section
// that shows attachment listing/viewing/downloading affordances
// (Award, Proposal, Subaward, Negotiation, Archived File Finder nav,
// dev Explorer) so the check and its fail-closed default live in one
// place.
export function useAttachmentAccess(): boolean {
  const [authorized, setAuthorized] = useState(false);

  useEffect(() => {
    let active = true;

    void hasAttachmentAccess().then((result) => {
      if (active) {
        setAuthorized(result);
      }
    });

    return () => {
      active = false;
    };
  }, []);

  return authorized;
}
