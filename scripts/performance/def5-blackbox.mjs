/*
 * DEF-5 black-box performance harness.
 *
 * Runs in the BROWSER CONSOLE of an authenticated Research Archive session,
 * so it needs no database access, no password, and no SQL endpoint. It
 * borrows the signed-in session's own token from localStorage and never
 * prints, stores or transmits it.
 *
 *   1. open the DEV site and sign in normally
 *   2. open the browser console
 *   3. paste this whole file and press Enter
 *   4. copy the printed table into the investigation report
 *
 * THE RULE THIS ENFORCES: a timing is recorded only when the response is
 * HTTP 200 AND its content validates. An earlier run of these measurements
 * silently recorded expired-token 401s as ~50 ms "fast" responses and very
 * nearly produced the conclusion that DEF-5 was mere cold-start warmup. Any
 * non-200, any unparseable body, any failed content check is reported as a
 * discarded sample and never averaged into a result.
 */
(async () => {
  const BASE = "https://api-dev.app-nprd.aws-cloud.bu.edu";

  const token = (() => {
    const key = Object.keys(localStorage).find((k) => k.endsWith(".accessToken"));
    return key ? localStorage.getItem(key) : null;
  })();
  if (!token) {
    console.error("Not signed in: no access token in localStorage. Sign in and retry.");
    return;
  }

  // Refuse to run on an expired token rather than produce 401 timings.
  const idKey = Object.keys(localStorage).find((k) => k.endsWith(".idToken"));
  if (idKey) {
    const claims = JSON.parse(atob(localStorage.getItem(idKey).split(".")[1]
      .replace(/-/g, "+").replace(/_/g, "/")));
    const minutesLeft = (claims.exp - Date.now() / 1000) / 60;
    if (minutesLeft <= 1) {
      console.error(`Token expires in ${minutesLeft.toFixed(1)} min. Re-authenticate first.`);
      return;
    }
    console.log(`Token valid for ${minutesLeft.toFixed(1)} more minutes.`);
  }

  const H = { Authorization: "Bearer " + token };

  /** One measurement. Returns a number only if 200 AND content validates. */
  async function measure(path, validate) {
    const started = performance.now();
    let response;
    try {
      response = await fetch(BASE + path, { headers: H, cache: "no-store" });
    } catch (error) {
      return { discarded: "network: " + String(error).slice(0, 40) };
    }
    const ms = Math.round(performance.now() - started);
    if (response.status !== 200) return { discarded: "HTTP " + response.status };
    let body;
    try { body = await response.json(); } catch { return { discarded: "non-JSON body" }; }
    if (!validate(body)) return { discarded: "content check failed" };
    return { ms };
  }

  const rowsPresent = (body) => {
    const rows = body?.content ?? body?.results?.content
      ?? (Array.isArray(body?.results) ? body.results : null);
    return Array.isArray(rows) && rows.length > 0;
  };
  const resultsArray = (body) => Array.isArray(body?.results);

  /** Repeat a measurement, reporting spread rather than a single number. */
  async function series(label, path, validate, runs = 5) {
    const kept = [];
    const discarded = [];
    for (let i = 0; i < runs; i++) {
      const outcome = await measure(path, validate);
      if (outcome.ms !== undefined) kept.push(outcome.ms);
      else discarded.push(outcome.discarded);
    }
    const sorted = [...kept].sort((a, b) => a - b);
    return {
      label,
      runs,
      counted: kept.length,
      discarded: discarded.length ? discarded.join("; ") : "",
      cold: kept[0] ?? null,
      min: sorted[0] ?? null,
      median: sorted[Math.floor(sorted.length / 2)] ?? null,
      max: sorted[sorted.length - 1] ?? null,
    };
  }

  const novel = () => Math.random().toString(36).slice(2, 10);
  const table = [];

  // Baselines: an authenticated single-record read. Establishes application,
  // auth and network overhead, so query cost can be separated from it.
  table.push(await series("baseline: award detail", "/api/awards/212137-00001",
    (b) => b?.current?.awardNumber === "212137-00001"));
  table.push(await series("baseline: negotiation detail", "/api/negotiations/12789",
    (b) => !!b));

  // A. Documents — the 40s+ case, and the module-filter contrast.
  table.push(await series("A1 documents, no query, size 25",
    "/api/v1/documents?page=0&size=25", rowsPresent));
  table.push(await series("A2 documents, query=NSF, size 25",
    "/api/v1/documents?page=0&size=25&query=NSF", rowsPresent));
  table.push(await series("A3 documents, query=NSF, size 1 (payload control)",
    "/api/v1/documents?page=0&size=1&query=NSF", rowsPresent));
  table.push(await series("A4 documents, query + module=PROPOSAL",
    "/api/v1/documents?page=0&size=25&query=NSF&module=PROPOSAL", rowsPresent));
  table.push(await series("A5 documents, module=PROPOSAL only",
    "/api/v1/documents?page=0&size=25&module=PROPOSAL", rowsPresent));
  table.push(await series("A6 documents, novel term (defeats any result cache)",
    `/api/v1/documents?page=0&size=25&query=${novel()}`, (b) => !!b));

  // B. Global Search — per-leg, to locate the dominant cost.
  table.push(await series("B1 global search, NSF", "/api/global-search?query=NSF", resultsArray));
  table.push(await series("B2 global search, non-matching",
    `/api/global-search?query=${novel()}`, resultsArray));
  table.push(await series("B3 global search, identifier (semantic short-circuits)",
    "/api/global-search?query=212137-00001", resultsArray));
  table.push(await series("B4 leg: award search", "/api/v1/awards/search?q=NSF&page=0&size=25", (b) => !!b));
  table.push(await series("B5 leg: proposal search", "/api/proposals/search?query=NSF&page=0&size=25", (b) => !!b));
  table.push(await series("B6 leg: negotiation search", "/api/negotiations?query=NSF&page=0&size=25", (b) => !!b));
  table.push(await series("B7 leg: subaward search", "/api/subawards?query=NSF&page=0&size=25", (b) => !!b));

  console.table(table);
  console.log("Copy the table above into the DEF-5 investigation report.");
  console.log("Any row with counted < runs has discarded samples; read the discarded column.");
  return table;
})();
