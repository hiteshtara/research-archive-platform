/*
 * "Back to search results" - the return path a detail page offers when,
 * and only when, the reader actually arrived from a search (QA TC-021).
 *
 * WHY THIS EXISTS RATHER THAN REUSING THE BREADCRUMB. The Award
 * breadcrumb walks the award *hierarchy* (root -> ... -> current),
 * which answers "where am I in this family?" and deliberately lets
 * someone move between related Awards without leaving. It is not a way
 * back to the result list, and a tester reasonably read it as one.
 * Those are two different questions, so they get two different
 * controls.
 *
 * WHY NOT BROWSER HISTORY. navigate(-1) is a guess: it lands wherever
 * the reader happened to be, which after a few breadcrumb hops is
 * another Award, not the search. The return target is therefore carried
 * forward explicitly from the page that owns it.
 *
 * WHAT IS PRESERVED. Every search page keeps its whole applied state in
 * the URL - query text, each structured filter, sort and page number
 * (useFilteredSearch is built on useSearchParams). So the search page's
 * own pathname plus search string IS the exact result list, pagination
 * included. Capturing that one string needs no separate bookkeeping and
 * cannot drift from what the reader was looking at.
 *
 * WHERE IT SURVIVES AND WHERE IT DOES NOT. Router state is not part of
 * the URL, so this does NOT preserve context across every route into a
 * detail page. Measured in a browser against this module, rather than
 * reasoned about:
 *
 *   ordinary click, search -> hierarchy -> dashboard   preserved
 *   breadcrumb hop between Awards, repeatedly          preserved
 *   reload of the dashboard (F5)                       preserved
 *   middle-click a result card into a new tab          LOST
 *   Cmd/Ctrl-click a result card into a new tab        LOST
 *   a pasted or bookmarked link to the dashboard       LOST
 *
 * Reload survives because router state lives in the history entry
 * (window.history.state), which the browser keeps across a reload and
 * React Router reads back on start. A new tab is a new history, so it
 * starts with nothing - the state was never in the link, which is also
 * why a copied result link carries no search terms.
 *
 * In the three LOST rows the link does not render at all. For a pasted
 * or bookmarked link that is simply correct: the reader did not come
 * from a search and there is nothing to go back to. For a new tab it is
 * a real limitation - the reader did come from a search, in the tab
 * they left behind - and the honest response is an absent link rather
 * than a guessed destination. Making that case work needs the target in
 * the URL; see docs/QA_DECISION_RECORD.md for the proposal and the
 * trade-off it carries.
 */

/** The one label, so the link reads identically wherever it appears. */
export const SEARCH_RETURN_LABEL = "Back to search results";

const STATE_KEY = "searchReturn";

/*
 * Iterated rather than matched with a regex: a character class spanning
 * the control range trips no-control-regex, and this reads the same way
 * as the API-side policy that refuses the same characters on input
 * (RequestParameterTextPolicy, QA TC-017).
 */
function hasControlCharacter(value) {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code < 0x20 || code === 0x7f) {
      return true;
    }
  }
  return false;
}

/*
 * Builds the router state a search page attaches to its result links.
 * `pathname` and `search` come straight from useLocation(), so the
 * captured target is whatever the reader currently has applied.
 */
export function buildSearchReturn(pathname, search = "") {
  if (typeof pathname !== "string" || pathname.length === 0) {
    return undefined;
  }
  const suffix = typeof search === "string" ? search : "";
  return { [STATE_KEY]: `${pathname}${suffix}` };
}

/*
 * Reads it back on a detail page, returning the path to link to, or
 * null when there is no search context.
 *
 * Router state is not trustworthy input: it persists in the history
 * entry and can be hand-crafted. Anything that is not a plain
 * site-internal absolute path is refused, so this can never become an
 * off-site redirect dressed up as a "back" link. Refused specifically:
 *
 *   - a scheme ("https:", "javascript:")
 *   - a protocol-relative or backslash host ("//evil", "/\evil" - both
 *     are treated as a host by some parsers)
 *   - a control character, which has no place in a path
 */
export function readSearchReturn(state) {
  const candidate = state?.[STATE_KEY];
  if (typeof candidate !== "string" || candidate.length === 0) {
    return null;
  }
  if (!candidate.startsWith("/")) {
    return null;
  }
  if (candidate.startsWith("//") || candidate.startsWith("/\\")) {
    return null;
  }
  if (hasControlCharacter(candidate)) {
    return null;
  }
  return candidate;
}

/*
 * Forwards an already-validated context across an in-app hop. Search
 * results land on the hierarchy page first, which then opens the
 * dashboard, and the breadcrumb hops between Awards after that - the
 * return target has to survive all of them, or the link appears and
 * then vanishes mid-journey.
 *
 * Returns undefined when there is nothing to carry, which is what
 * navigate() and Link expect for "no state".
 */
export function forwardSearchReturn(state) {
  const path = readSearchReturn(state);
  return path ? { [STATE_KEY]: path } : undefined;
}
