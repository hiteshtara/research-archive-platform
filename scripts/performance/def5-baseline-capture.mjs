/*
 * DEF-5 revision 93 BASELINE CAPTURE.
 *
 * Produces the "before" half of every acceptance gate in
 * docs/performance/DEF5_FIX_AND_ACCEPTANCE.md. It must be run against
 * the CURRENTLY DEPLOYED revision 93, BEFORE any deployment of the
 * fix - gates 15-18 compare result identity before and after, and once
 * a new revision is serving, the "before" is gone.
 *
 * Runs in the browser console of an authenticated session. It borrows
 * the session's own token from localStorage and never prints, stores or
 * transmits it.
 *
 * WHAT IT REFUSES TO RECORD (every one of these has bitten this
 * investigation at least once):
 *
 *   - any non-200, so an expired-token 401 can never become a "fast"
 *     timing. An earlier run recorded 401s as ~50 ms and nearly
 *     concluded DEF-5 was cold-start warmup.
 *   - any response whose content-type is not JSON. A relative /api
 *     fetch from the UI origin returns the SPA's index.html with
 *     HTTP 200; five such responses were once recorded as 11-203 ms
 *     "Documents" timings.
 *   - any body that fails its own per-row content check.
 *   - any request that could not start with enough token headroom to
 *     finish.
 *
 * Discarded samples are reported as discards, never averaged in.
 */
(async () => {
  const BASE = "https://api-dev.app-nprd.aws-cloud.bu.edu";
  const DOCS = "/api/v1/documents?page=0&size=25&includeAnyUnit=false&piOnly=false";

  const tokenKey = Object.keys(localStorage).find((k) => k.endsWith(".accessToken"));
  const idKey = Object.keys(localStorage).find((k) => k.endsWith(".idToken"));
  if (!tokenKey || !idKey) {
    console.error("Not signed in. Sign in and retry.");
    return;
  }
  const H = { Authorization: "Bearer " + localStorage.getItem(tokenKey) };
  const claims = JSON.parse(atob(localStorage.getItem(idKey).split(".")[1]
    .replace(/-/g, "+").replace(/_/g, "/")));
  const expiresAt = claims.exp * 1000;

  // Never START a request that cannot finish on this token. The slowest
  // known row is ~46 s, so 90 s of headroom is the floor.
  const HEADROOM_MS = 90_000;
  const canStart = () => expiresAt - Date.now() > HEADROOM_MS;
  const minutesLeft = () => ((expiresAt - Date.now()) / 60000).toFixed(1);

  console.log(`Token valid for ${minutesLeft()} more minutes.`);

  /** One sample. Returns {ms} only on 200 + JSON content-type + content check. */
  async function sample(path, validate) {
    if (!canStart()) return { discarded: `token headroom < ${HEADROOM_MS / 1000}s` };
    const started = performance.now();
    let response;
    try {
      response = await fetch(BASE + path, { headers: H, cache: "no-store" });
    } catch (error) {
      return { discarded: "network: " + String(error).slice(0, 40) };
    }
    const ms = Math.round(performance.now() - started);
    if (response.status !== 200) return { discarded: "HTTP " + response.status, ms };
    const contentType = response.headers.get("content-type") || "";
    if (!contentType.includes("application/json")) {
      return { discarded: "content-type " + contentType, ms };
    }
    let body;
    try { body = await response.json(); } catch { return { discarded: "non-JSON body", ms }; }
    const verdict = validate(body);
    if (verdict !== true) return { discarded: "content: " + verdict, ms };
    return { ms, body };
  }

  /** Repeated samples -> median/min/max/n, with discards listed separately. */
  async function series(label, path, validate, repeats) {
    const kept = [];
    const discarded = [];
    for (let i = 0; i < repeats; i++) {
      const result = await sample(path, validate);
      if (result.ms !== undefined && !result.discarded) kept.push(result.ms);
      else discarded.push(result.discarded);
    }
    const sorted = [...kept].sort((a, b) => a - b);
    const median = sorted.length
      ? (sorted.length % 2
          ? sorted[(sorted.length - 1) / 2]
          : Math.round((sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2))
      : null;
    const row = {
      label,
      n: kept.length,
      median,
      min: sorted[0] ?? null,
      max: sorted[sorted.length - 1] ?? null,
      samples: kept,
      discarded,
    };
    console.log(`${label}: n=${row.n} median=${row.median} min=${row.min} max=${row.max}`
      + (discarded.length ? ` DISCARDED=${JSON.stringify(discarded)}` : ""));
    return row;
  }

  // --- content checks -------------------------------------------------

  const pagedDocuments = (body) => {
    const r = body?.results;
    if (!r || !Array.isArray(r.content)) return "no results.content array";
    if (typeof r.totalElements !== "number") return "no numeric totalElements";
    if (!Array.isArray(body.moduleFacets)) return "no moduleFacets array";
    return true;
  };
  // A text query must actually be reflected in every row it returns.
  const documentsMatching = (term) => (body) => {
    const basic = pagedDocuments(body);
    if (basic !== true) return basic;
    const upper = term.toUpperCase();
    const bad = body.results.content.find((row) =>
      ![row.documentNumber, row.title, row.businessRecordNumber]
        .some((v) => typeof v === "string" && v.toUpperCase().includes(upper)));
    return bad ? `row does not contain ${term}: ${bad.documentNumber}` : true;
  };
  const documentsInModule = (term, module) => (body) => {
    const matched = documentsMatching(term)(body);
    if (matched !== true) return matched;
    const bad = body.results.content.find((row) => row.module !== module);
    return bad ? `row not in ${module}: ${bad.module}` : true;
  };
  const globalSearch = (body) =>
    Array.isArray(body?.results) ? true : "no results array";
  const awardDetail = (body) =>
    body && typeof body.awardNumber === "string" ? true : "no awardNumber";

  // --- identity snapshot for the correctness gates --------------------

  /*
   * Gate 16 compares the full result MULTISET, not page 0: the fix adds
   * source_record_id as a sort tie-breaker, so where rows share both
   * document_number and module the deployed path's order is not
   * deterministic and a page boundary can legitimately differ. The
   * duplicate-pair pre-check below records whether that applies here.
   */
  async function identity(label, path) {
    const result = await sample(path + "&size=100", pagedDocuments);
    if (result.discarded) {
      console.warn(`identity ${label}: DISCARDED ${result.discarded}`);
      return { label, discarded: result.discarded };
    }
    const rows = result.body.results.content.map((r) =>
      `${r.module}|${r.documentNumber}|${r.targetId}`);
    const pairs = result.body.results.content.map((r) => `${r.module}|${r.documentNumber}`);
    const duplicatePairs = pairs.filter((p, i) => pairs.indexOf(p) !== i);
    const snapshot = {
      label,
      totalElements: result.body.results.totalElements,
      returned: rows.length,
      moduleFacets: result.body.moduleFacets,
      rowsSorted: [...rows].sort(),
      duplicateModuleDocumentPairs: [...new Set(duplicatePairs)],
    };
    console.log(`identity ${label}: total=${snapshot.totalElements}`
      + ` returned=${snapshot.returned}`
      + ` duplicatePairs=${snapshot.duplicateModuleDocumentPairs.length}`);
    return snapshot;
  }

  // --- the capture ----------------------------------------------------

  const timings = [];
  const FIVE = 5;
  const THREE = 3;

  // Gates 9-14: the timing baseline.
  timings.push(await series("gate09 documents query=NSF all modules",
    `${DOCS}&query=NSF`, documentsMatching("NSF"), FIVE));
  timings.push(await series("gate10 documents query=NSF module=AWARD",
    `${DOCS}&query=NSF&module=AWARD`, documentsInModule("NSF", "AWARD"), FIVE));
  timings.push(await series("gate11 documents query=NSF module=PROPOSAL",
    `${DOCS}&query=NSF&module=PROPOSAL`, documentsInModule("NSF", "PROPOSAL"), FIVE));
  timings.push(await series("gate12 documents unfiltered default page",
    DOCS, pagedDocuments, FIVE));
  // Gate 13 has no prior baseline: it must stay on the heavy path, so it
  // is the no-regression control for the part of the fix that changes
  // nothing.
  timings.push(await series("gate13 documents personName=SMITH (heavy path control)",
    `${DOCS}&personName=SMITH`, pagedDocuments, THREE));
  timings.push(await series("gate14 unrelated endpoint baseline",
    "/api/awards/212137-00001", awardDetail, FIVE));

  // Task A: remaining module legs, for completeness.
  timings.push(await series("A5 documents query=NSF module=NEGOTIATION",
    `${DOCS}&query=NSF&module=NEGOTIATION`, pagedDocuments, THREE));
  timings.push(await series("A6 documents query=NSF module=SUBAWARD",
    `${DOCS}&query=NSF&module=SUBAWARD`, documentsInModule("NSF", "SUBAWARD"), THREE));

  // Task B: Global Search, by leg. Separate cause; captured so the fix
  // can be shown NOT to have changed it.
  timings.push(await series("B1 global search NSF",
    "/api/global-search?query=NSF", globalSearch, THREE));
  timings.push(await series("B2 global search non-matching",
    "/api/global-search?query=zzzqqqxyz123", globalSearch, THREE));
  timings.push(await series("B3 global search identifier",
    "/api/global-search?query=212137-00001", globalSearch, THREE));
  for (const module of ["AWARD", "PROPOSAL", "NEGOTIATION", "SUBAWARD"]) {
    timings.push(await series(`B global search NSF leg ${module}`,
      `/api/global-search?query=NSF&modules=${module}`, globalSearch, THREE));
  }

  // Gates 15-18: result identity, to be re-captured after deployment.
  const identities = [];
  identities.push(await identity("query=NSF", `${DOCS}&query=NSF`));
  identities.push(await identity("query=NSF module=AWARD", `${DOCS}&query=NSF&module=AWARD`));
  identities.push(await identity("unfiltered", DOCS));
  identities.push(await identity("personName=SMITH", `${DOCS}&personName=SMITH`));

  const out = {
    capturedAt: new Date().toISOString(),
    apiBase: BASE,
    tokenMinutesLeftAtEnd: Number(minutesLeft()),
    timings,
    identities,
  };
  console.log("=== DEF-5 BASELINE (copy this) ===");
  console.log(JSON.stringify(out, null, 2));
  return out;
})();
