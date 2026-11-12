import { useQuery } from "@tanstack/react-query";

import { readCapabilities } from "./securityAdminPresentation.mjs";
import type { Capabilities } from "./securityAdminPresentation.d.mts";

/*
 * What the signed-in caller may do, as the SERVER sees it.
 *
 * This is the only source of administrator status in the UI. It exists because
 * the administrator API tree is gated on enforcement-enabled and 404s while
 * enforcement is off, so asking it "am I an admin?" cannot distinguish
 * "feature disabled" from "you are not an administrator".
 *
 * It grants nothing. Every administrator endpoint independently calls
 * requireAdministrator(actor); hiding or showing UI changes none of that.
 *
 * no-store on purpose: capability is security state, and a stale `true` after
 * a revoked membership is exactly the wrong thing to cache.
 */
export function useCapabilities(): Capabilities {
  const query = useQuery({
    queryKey: ["me", "capabilities"],
    queryFn: async () => {
      const response = await fetch("/api/v1/me/capabilities", {
        headers: { Accept: "application/json" },
        cache: "no-store",
      });
      // A 404 means the endpoint is not deployed yet; a 401 means the session
      // is gone. Neither is an administrator, and neither is a crash.
      if (!response.ok) {
        return null;
      }
      return (await response.json()) as unknown;
    },
    staleTime: 0,
    retry: false,
  });

  return readCapabilities(query.data ?? null, {
    isLoading: query.isLoading,
    isError: query.isError,
  });
}
