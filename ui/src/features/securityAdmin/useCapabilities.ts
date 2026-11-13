import { useQuery } from "@tanstack/react-query";

import { getCapabilities } from "../../api/client";

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
    // getCapabilities() lives in the shared API client so this call gets
    // the same absolute API base URL and bearer token as every other
    // request. The earlier relative fetch here reached the single-page
    // app's own index.html on the deployed origin - HTTP 200, text/html -
    // and carried no Authorization header, so it could never have reached
    // the endpoint even once it ships.
    //
    // A 404 (not deployed yet), a 401 (session gone), a non-JSON body and
    // a network failure all arrive as null. None is an administrator and
    // none is a crash.
    queryFn: getCapabilities,
    staleTime: 0,
    retry: false,
  });

  return readCapabilities(query.data ?? null, {
    isLoading: query.isLoading,
    isError: query.isError,
  });
}
