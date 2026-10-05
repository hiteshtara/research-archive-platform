/**
 * Presentation helpers shared by every archive search page.
 *
 * A user who learns one archive search page should immediately understand
 * every other one. The business data differs per module; the phrasing,
 * the identifier treatment and the status treatment should not. These are
 * the pure parts of that, unit-testable without a component-render
 * harness (this repo has none).
 */

/**
 * Splits a Kuali status that carries a leading ordinal code.
 *
 * Subaward's real archived `status_description` values are numbered -
 * "04. PI/DA", "07. Executed", "09. Temporarily Cancelled" - and BU reads
 * the words, not the number. The number is real metadata though, so it is
 * returned separately rather than discarded, for a tooltip or secondary
 * line.
 *
 * Award, Proposal and Negotiation statuses carry no ordinal, so this is a
 * no-op for them and cannot change how they render.
 */
export function splitStatusCode(status) {
  if (typeof status !== "string") {
    return { code: null, label: status ?? null };
  }

  const match = status.match(/^\s*(\d{1,3})\s*[.)-]\s*(.+?)\s*$/);
  if (!match) {
    return { code: null, label: status.trim() === "" ? status : status };
  }

  return { code: match[1], label: match[2] };
}

/**
 * "24 awards found" / "1 award found" / "0 awards found".
 *
 * Rendered in an `overline` Typography, which upper-cases it in the theme;
 * the string itself stays sentence-case so it is readable anywhere else
 * and so screen readers do not spell it out.
 */
export function describeResultCount({
  total = 0,
  singular = "result",
  plural,
} = {}) {
  const noun = total === 1 ? singular : (plural ?? `${singular}s`);
  return `${Number(total).toLocaleString()} ${noun} found`;
}

/**
 * Human-readable value first, identifier second.
 *
 * BU should never have to interpret "1242040000" when "ENG BIOMEDICAL
 * ENG" is a verified name for it. The name is primary and the code is
 * secondary metadata; the code is preserved exactly as a string so
 * leading zeroes and formatting survive. When only one of the two exists,
 * that one stands alone - a missing name is never faked from the code.
 */
export function formatNamedIdentifier(name, identifier, { fallback = "—" } = {}) {
  const primaryName = typeof name === "string" ? name.trim() : "";
  const code = typeof identifier === "string"
    ? identifier.trim()
    : identifier == null
      ? ""
      : String(identifier);

  if (!primaryName && !code) {
    return { primary: fallback, secondary: null };
  }
  if (!primaryName) {
    return { primary: code, secondary: null };
  }
  if (!code) {
    return { primary: primaryName, secondary: null };
  }
  return { primary: primaryName, secondary: code };
}

/**
 * Joins the card's third line, dropping anything missing rather than
 * leaving "·  ·" gaps or printing "null".
 */
export function joinMetadata(parts, separator = " · ") {
  return (parts ?? [])
    .map((part) => (typeof part === "string" ? part.trim() : part))
    .filter((part) => part !== null && part !== undefined && part !== "")
    .join(separator);
}

/**
 * Reads search state out of URLSearchParams.
 *
 * Search state belongs in the URL so refresh, Back and a copied link all
 * restore the same search. `page` is clamped to a non-negative integer
 * because it is user-editable in the address bar.
 */
export function readSearchParams(searchParams, { pageKey = "page" } = {}) {
  const get = (key) =>
    typeof searchParams?.get === "function" ? searchParams.get(key) : null;

  const rawPage = Number(get(pageKey) ?? "0");
  const page = Number.isFinite(rawPage) && rawPage > 0 ? Math.floor(rawPage) : 0;

  return { query: get("q") ?? "", page };
}

/**
 * Builds the URL params for a search, omitting empties so a cleared
 * search produces a clean "?"-less URL rather than "?q=&page=0".
 */
export function buildSearchParams({ query, page = 0, extra } = {}) {
  const params = {};
  const trimmed = typeof query === "string" ? query.trim() : "";

  if (trimmed) {
    params.q = trimmed;
  }
  if (page > 0) {
    params.page = String(page);
  }
  for (const [key, value] of Object.entries(extra ?? {})) {
    const serialized = typeof value === "string" ? value.trim() : value;
    if (serialized !== "" && serialized !== null && serialized !== undefined) {
      params[key] = String(serialized);
    }
  }
  return params;
}

/**
 * Which of the four search states a page is in.
 *
 * "initial" is why primary search pages no longer preload results: before
 * a search there is nothing to show but the hero, so a module must not
 * render 10,775 rows just because the data exists.
 */
export function resolveSearchState({
  hasSearched = false,
  isLoading = false,
  isError = false,
  resultCount = 0,
} = {}) {
  if (!hasSearched) {
    return "initial";
  }
  if (isError) {
    return "error";
  }
  if (isLoading) {
    return "loading";
  }
  return resultCount > 0 ? "results" : "empty";
}

/*
 * Telling a rejected search apart from a broken one.
 *
 * QA TC-017: a search carrying an invisible control character used to
 * return a server error, and the page said "Unable to search right now.
 * Try again in a moment." The API now refuses that input properly, but
 * the same sentence was still shown - so the page told the reader to
 * wait and retry something that will be refused identically every time,
 * and never mentioned the only thing that would help, which is changing
 * what they entered.
 *
 * VALIDATION_ERROR is a SHARED code, not a specific diagnosis. Measured
 * against dev, the same code comes back for at least:
 *
 *   - a control character in the query or in a structured filter
 *     ("Parameter 'q' contains a character that is not allowed: U+0000")
 *   - a Global Search query past its length limit
 *     ("search.query: size must be between 2 and 200")
 *   - an out-of-range paging parameter
 *     ("search.page: must be greater than or equal to 0")
 *
 * So the guidance must not name a cause. Saying "invisible character"
 * would be wrong for two of those three, and a confidently wrong
 * explanation sends someone hunting for a problem they do not have.
 *
 * Both the status and the code are required. The code alone is not
 * enough to conclude the input was refused: a future endpoint could
 * return it alongside a different status, and a 400 on its own can
 * still be a genuine fault.
 */
const INPUT_REJECTED_CODE = "VALIDATION_ERROR";
const INPUT_REJECTED_STATUS = 400;

export function isSearchInputRejection(error) {
  return (
    Boolean(error) &&
    error.status === INPUT_REJECTED_STATUS &&
    error.code === INPUT_REJECTED_CODE
  );
}

/*
 * Deliberately our own wording rather than the API's sentence: the
 * server's text names parameters, code points and constraint internals
 * ("search.query: size must be between 2 and 200"), which is right in a
 * response body and unreadable under a search box. Neither version
 * repeats what was typed - echoing the value back is how unprintable or
 * hostile input ends up rendered in the page - and this one names no
 * cause it has not established, only where to look.
 */
export const SEARCH_INPUT_REJECTED_MESSAGE =
  "This search wasn't accepted as entered. Check the search box and any " +
  "filters you have set, adjust what you entered, then search again.";

/*
 * The message for a failed search. A refused input gets guidance the
 * reader can act on; everything else - a server fault, a dropped
 * connection, a timeout - keeps the module's own generic sentence,
 * because for those, waiting and retrying genuinely is the advice.
 */
export function searchErrorMessage(error, genericMessage) {
  return isSearchInputRejection(error)
    ? SEARCH_INPUT_REJECTED_MESSAGE
    : genericMessage;
}
