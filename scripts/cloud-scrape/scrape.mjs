// Scheduled, server-side cause-list check for CCMS.
//
// Runs on a GitHub Actions cron schedule (see ../../.github/workflows/
// cloud-scrape.yml) so a match is found even when neither the web app nor
// the Android app is open. Reads every signed-in user's keywords/cases/
// pending files from their Firestore document, fetches the LHC urgent
// cause list, matches, and writes any new hits back — plus an FCM push if
// the user's devices have registered for one.
//
// SCOPE NOTE: this intentionally ports only the ESSENTIAL matching already
// proven in casediary.html's scanPaste() — keyword/case-ref/pending-file
// token matching and the principal-seat bench filter (isOtherBench). The
// more elaborate passes used in-app (C.M./application folding into its
// main case, judge-name and hearing-date extraction, wrapped-line merging)
// are deliberately NOT ported here for this first version, to keep this
// new, unverified code path small and reviewable. A hit this script finds
// still shows up correctly in the Diary — it just won't have those extra
// touches until a later round folds them in too.
//
// VERIFICATION NOTE: the matching functions below are a line-for-line port
// of casediary.html's and have been exercised with synthetic rows, but the
// live fetch against data.lhc.gov.pk has NOT been verified end-to-end —
// this sandbox's network policy blocks every *.lhc.gov.pk host, so there
// was no way to test the real page here. GitHub's own runners have normal
// internet access, so this should work once deployed, but the first few
// scheduled runs are worth checking (Actions tab -> this workflow's logs).

import * as cheerio from "cheerio";
import { initializeApp, cert } from "firebase-admin/app";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";

const CAUSE_LIST_URL =
  process.env.LHC_CAUSE_LIST_URL ||
  "https://data.lhc.gov.pk/index.php/case_management/urgent_cause_list_final";

const MAX_HITS_STORED = 400;

// ------------------------------------------------------------------ matcher
// Ported from casediary.html's matcher section — keep in sync if that file's
// normalize()/compile()/findHits()/isOtherBench() ever change.

export function normalize(s) {
  let out = "";
  for (const ch of String(s).toLowerCase()) {
    out += /[a-z0-9؀-ۿ]/.test(ch) ? ch : " ";
  }
  return out.replace(/\s+/g, " ").trim();
}

function meaningfulTokens(term) {
  const all = normalize(term).split(" ").filter(Boolean);
  const strong = all.filter((t) => t.length >= 3 || /\d/.test(t));
  return strong.length ? strong : all;
}

function usableTokens(term) {
  const t = meaningfulTokens(term);
  if (!t.length) return null;
  if (t.length === 1 && t[0].length < 3) return null;
  return t;
}

function caseRef(c) {
  let s = "";
  if (c.caseType) s += c.caseType.trim() + " ";
  if (c.caseNo) s += c.caseNo.trim();
  if (c.caseYear) s += "/" + c.caseYear.trim();
  return s.trim();
}

const STOPWORDS = new Set(["vs", "etc", "and", "the", "of", "in", "re", "mst"]);
function pendingTokens(title) {
  return normalize(title).split(" ").filter((t) => t.length >= 3 && !STOPWORDS.has(t));
}

export function compile(keys, cases, pending, pendingFilesEnabled) {
  const probes = [];
  for (const k of keys || []) {
    if (k.enabled === false) continue;
    const t = usableTokens(k.term);
    if (t) probes.push({ label: k.term, kind: k.kind, caseId: 0, pendingId: 0, tokens: t, key: "key:" + normalize(k.term) });
  }
  for (const c of cases || []) {
    if (c.status === "ARCHIVED") continue;
    if (c.watched === false) continue;
    const ref = caseRef(c);
    if (c.caseNo && c.caseYear) {
      const t = usableTokens(c.caseNo + " " + c.caseYear);
      if (t) probes.push({ label: ref, kind: "CASE", caseId: c.id, pendingId: 0, tokens: t, key: "case:" + normalize(ref) });
    }
    const party = (c.petitioner || "").trim();
    if (party.length >= 4) {
      const t = usableTokens(party);
      if (t) {
        const label = ref || party;
        probes.push({ label, kind: "PARTY", caseId: c.id, pendingId: 0, tokens: t, key: "party:" + normalize(label) });
      }
    }
  }
  const pendingList = pendingFilesEnabled === false ? [] : pending || [];
  for (const pf of pendingList) {
    const t = pendingTokens(pf.title);
    if (!t.length) continue;
    probes.push({ label: pf.title, kind: "PENDING", caseId: 0, pendingId: pf.id, tokens: t, key: "pending:" + pf.id });
    if (pf.caseNo && pf.caseYear) {
      const numToks = usableTokens(pf.caseNo + " " + pf.caseYear);
      if (numToks) {
        const ref = [pf.caseType, pf.caseNo].filter(Boolean).join(" ").trim() + "/" + pf.caseYear;
        probes.push({ label: ref, kind: "CASE", caseId: 0, pendingId: pf.id, tokens: numToks, key: "pendingcase:" + pf.id });
      }
    }
  }
  return probes;
}

export function findHits(row, probes) {
  if (!probes.length) return [];
  const norm = normalize(row);
  if (norm.length < 6) return [];
  const set = new Set(norm.split(" ").filter(Boolean));
  const present = (t) => set.has(t) || (t.length >= 6 && norm.indexOf(t) !== -1);
  const out = new Map();
  for (const p of probes) {
    const ok = p.tokens.every(present);
    if (ok && !out.has(p.key)) out.set(p.key, { term: p.label, kind: p.kind, caseId: p.caseId, pendingId: p.pendingId || 0 });
  }
  return [...out.values()];
}

const BRACKET_CORE = "\\[\\s*(\\d{2,7})\\s*(?:-\\s*[A-Za-z]{1,4}\\s*)?-\\s*(\\d{2,4})\\s*\\]";
const BRACKETED_CASE_RE = new RegExp(BRACKET_CORE);
export function isOtherBench(row) {
  if (BRACKETED_CASE_RE.test(row)) return true;
  return /\b(bahawalpur|multan|rawalpindi)\b/i.test(row);
}

export function hashOf(parts) {
  const joined = parts.map(normalize).join("|");
  let h = 5381;
  for (let i = 0; i < joined.length; i++) h = ((h * 33) ^ joined.charCodeAt(i)) >>> 0;
  return h.toString(36) + "-" + joined.length;
}

// --------------------------------------------------------------- HTML rows
// Mirrors Android's CauseListScraper.extractRows(): table rows first (cells
// joined with " | "), then list/paragraph-like elements, then a last-resort
// whole-page-text fallback if nothing structured was found at all.

function collapse(s) {
  return String(s).replace(/\s+/g, " ").trim();
}

export function extractRows(html) {
  const $ = cheerio.load(html);
  const out = new Set();

  $("tr").each((_, el) => {
    const $el = $(el);
    const cells = $el.find("th, td");
    const text = cells.length
      ? cells.toArray().map((c) => collapse($(c).text())).join(" | ")
      : collapse($el.text());
    if (text.length >= 8) out.add(text);
  });

  $("li, p, div.row, .cause-list-item, article").each((_, el) => {
    const $el = $(el);
    if ($el.find("tr").length) return; // already covered above
    const text = collapse($el.text());
    if (text.length >= 8 && text.length <= 600) out.add(text);
  });

  if (out.size === 0) {
    collapse($("body").text())
      .split("\n")
      .forEach((line) => {
        const text = collapse(line);
        if (text.length >= 8) out.add(text);
      });
  }

  return [...out];
}

// ------------------------------------------------------------------- main

async function fetchCauseList() {
  const res = await fetch(CAUSE_LIST_URL, {
    headers: { "User-Agent": "Mozilla/5.0 (compatible; CCMSCauseListCheck/1.0)" },
  });
  if (!res.ok) throw new Error(`Fetch failed: HTTP ${res.status}`);
  return await res.text();
}

async function run() {
  const keyJson = process.env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!keyJson) {
    console.log("FIREBASE_SERVICE_ACCOUNT_KEY is not set yet — cloud sync hasn't been configured. Skipping this run (not a failure).");
    return;
  }

  const serviceAccount = JSON.parse(keyJson);
  initializeApp({ credential: cert(serviceAccount) });
  const db = getFirestore();

  let html;
  try {
    html = await fetchCauseList();
  } catch (e) {
    console.error("Could not fetch the cause list this run:", e.message);
    return; // transient network/site issue — try again next scheduled run
  }

  const rows = extractRows(html);
  console.log(`Fetched cause list: ${rows.length} row(s) extracted.`);

  const usersSnap = await db.collection("users").get();
  if (usersSnap.empty) {
    console.log("No users signed up for cloud sync yet.");
    return;
  }

  for (const doc of usersSnap.docs) {
    const uid = doc.id;
    try {
      await scanForUser(db, uid, doc.data(), rows);
    } catch (e) {
      console.error(`User ${uid}: error during scan — ${e.message}`);
    }
  }
}

export async function scanForUser(db, uid, data, rows) {
  if (data?.settings?.cloudScrapeEnabled !== true) {
    console.log(`User ${uid}: cloud scraping not enabled, skipping.`);
    return;
  }

  const principalSeatOnly = data.settings?.principalSeatOnly !== false;
  const probes = compile(data.keys, data.cases, data.pending, data.settings?.pendingFilesEnabled);
  if (!probes.length) {
    console.log(`User ${uid}: no keywords/cases/pending files to check.`);
    return;
  }

  const existingHashes = new Set((data.hits || []).map((h) => h.hash));
  const now = Date.now();
  const newHits = [];
  let skippedBench = 0;

  for (const row of rows) {
    if (principalSeatOnly && isOtherBench(row)) {
      skippedBench++;
      continue;
    }
    const terms = findHits(row, probes);
    if (!terms.length) continue;

    const caseId = terms.find((t) => t.caseId)?.caseId || 0;
    const pendingId = terms.find((t) => t.pendingId)?.pendingId || 0;
    const clipped = row.length > 400 ? row.slice(0, 400) + "…" : row;
    const hash = hashOf(["cloud", clipped]);
    if (existingHashes.has(hash)) continue;

    existingHashes.add(hash);
    newHits.push({
      hash,
      terms,
      caseId,
      pendingId,
      source: "Cloud check",
      raw: clipped,
      related: [],
      listDate: "",
      foundAt: now,
    });
  }

  console.log(`User ${uid}: ${newHits.length} new hit(s) found (${skippedBench} other-bench row(s) skipped).`);
  if (!newHits.length) return;

  let hits = [...(data.hits || []), ...newHits];
  if (hits.length > MAX_HITS_STORED) hits = hits.slice(hits.length - MAX_HITS_STORED);

  await db.collection("users").doc(uid).set(
    { hits, updatedAt: FieldValue.serverTimestamp() },
    { merge: true }
  );

  await notifyUser(uid, data, newHits.length);
}

async function notifyUser(uid, data, count) {
  const tokens = Array.isArray(data.fcmTokens) ? data.fcmTokens.filter(Boolean) : [];
  if (!tokens.length) {
    console.log(`User ${uid}: no registered device for push, skipping notification.`);
    return;
  }
  try {
    await getMessaging().sendEachForMulticast({
      tokens,
      notification: {
        title: count === 1 ? "Your case is fixed" : `${count} of your cases are fixed`,
        body: "Found during a scheduled cause-list check. Open CCMS to review.",
      },
    });
  } catch (e) {
    console.error(`User ${uid}: push notification failed — ${e.message}`);
  }
}

run().catch((e) => {
  console.error("Unhandled error:", e);
  process.exitCode = 1;
});
