/**
 * AniMusic anonymous active-user count.
 *
 *   POST /ping   {"id": "<random UUID>", "appVersion": "1.0.3", "androidSdk": 34}
 *   GET  /stats?key=<STATS_KEY>
 *
 * What is stored: one row per install id per UTC day, holding that day, the app
 * version and the Android SDK level, plus one lifetime counter. Nothing else:
 * this Worker never reads the caller's IP address or any other request header
 * into storage. Rows older than RETENTION_DAYS are deleted every day.
 *
 * The id is a random UUID the app makes on first launch. It is tied to nothing
 * (no account, no device identifier) and the app can turn pings off entirely.
 */

export const RETENTION_DAYS = 90;
const MAX_BODY_BYTES = 512;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const VERSION = /^[0-9A-Za-z.+_-]{1,20}$/;

/** The UTC calendar day of [date] as YYYY-MM-DD. */
export function utcDay(date) {
  return date.toISOString().slice(0, 10);
}

/** The UTC day [days] before [date]. */
export function daysBefore(date, days) {
  return utcDay(new Date(date.getTime() - days * 86_400_000));
}

/**
 * A ping body made safe to store, or null when it is not one. The id must be a
 * UUID (lower-cased here), the version a short plain token, the SDK a sane
 * integer. Anything else in the body is ignored, never stored.
 */
export function validatePing(body) {
  if (body === null || typeof body !== "object" || Array.isArray(body)) return null;
  const id = typeof body.id === "string" ? body.id.trim().toLowerCase() : "";
  const appVersion = typeof body.appVersion === "string" ? body.appVersion.trim() : "";
  const androidSdk = body.androidSdk;
  if (!UUID.test(id)) return null;
  if (!VERSION.test(appVersion)) return null;
  if (!Number.isInteger(androidSdk) || androidSdk < 1 || androidSdk > 100) return null;
  return { id, appVersion, androidSdk };
}

/** Constant-time comparison of the given stats key with the configured one. */
export function keyMatches(given, expected) {
  if (typeof given !== "string" || typeof expected !== "string" || expected.length === 0) return false;
  const a = new TextEncoder().encode(given);
  const b = new TextEncoder().encode(expected);
  let diff = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) diff |= (a[i] ?? 0) ^ (b[i] ?? 0);
  return diff === 0;
}

async function readJson(request) {
  const length = Number(request.headers.get("content-length") || "0");
  if (length > MAX_BODY_BYTES) return undefined;
  const text = await request.text();
  if (text.length > MAX_BODY_BYTES) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

/** Records one ping: at most one row per id per UTC day. */
export async function recordPing(db, ping, now = new Date()) {
  const day = utcDay(now);
  // Seen within the retention window? If not, it's a new install (or one
  // idle for more than RETENTION_DAYS) and the lifetime counter moves.
  const seen = await db.prepare("SELECT 1 FROM pings WHERE id = ?1 LIMIT 1").bind(ping.id).first();
  const statements = [
    db
      .prepare(
        `INSERT INTO pings (id, day, app_version, android_sdk) VALUES (?1, ?2, ?3, ?4)
         ON CONFLICT (id, day) DO UPDATE SET app_version = excluded.app_version, android_sdk = excluded.android_sdk`,
      )
      .bind(ping.id, day, ping.appVersion, ping.androidSdk),
  ];
  if (!seen) {
    statements.push(
      db
        .prepare(
          `INSERT INTO counters (name, value) VALUES ('installs', 1)
           ON CONFLICT (name) DO UPDATE SET value = value + 1`,
        ),
    );
  }
  await db.batch(statements);
}

/** Deletes everything older than the retention window. */
export async function prune(db, now = new Date()) {
  await db.prepare("DELETE FROM pings WHERE day < ?1").bind(daysBefore(now, RETENTION_DAYS)).run();
}

/** Daily actives (30 days), monthly actives, installs and versions. */
export async function stats(db, now = new Date()) {
  const from30 = daysBefore(now, 29); // today plus the 29 days before it
  const from90 = daysBefore(now, RETENTION_DAYS - 1);
  const [daily, monthly, recent, lifetime, versions] = await db.batch([
    db.prepare("SELECT day, COUNT(*) AS users FROM pings WHERE day >= ?1 GROUP BY day ORDER BY day").bind(from30),
    db.prepare("SELECT COUNT(DISTINCT id) AS users FROM pings WHERE day >= ?1").bind(from30),
    db.prepare("SELECT COUNT(DISTINCT id) AS users FROM pings WHERE day >= ?1").bind(from90),
    db.prepare("SELECT value FROM counters WHERE name = 'installs'"),
    // Each install counted once, under the version it reported most recently.
    db
      .prepare(
        `SELECT p.app_version AS version, COUNT(*) AS users
         FROM pings p JOIN (SELECT id, MAX(day) AS last FROM pings WHERE day >= ?1 GROUP BY id) m
           ON p.id = m.id AND p.day = m.last
         GROUP BY p.app_version ORDER BY users DESC`,
      )
      .bind(from30),
  ]);
  return {
    generatedAt: now.toISOString(),
    dailyActiveUsers: daily.results,
    monthlyActiveUsers: monthly.results[0]?.users ?? 0,
    activeInstallsLast90Days: recent.results[0]?.users ?? 0,
    totalUniqueInstalls: lifetime.results[0]?.value ?? 0,
    totalUniqueInstallsNote:
      "Lifetime count of new install ids. Ids are deleted after 90 days idle, so an install that returns after that is counted again.",
    versionsLast30Days: versions.results,
  };
}

const json = (status, body) =>
  new Response(body === null ? null : JSON.stringify(body, null, 2), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/ping") {
      if (request.method !== "POST") return json(405, { error: "POST only" });
      const ping = validatePing(await readJson(request));
      if (!ping) return json(400, { error: "bad ping" });
      await recordPing(env.DB, ping);
      return json(204, null);
    }
    if (url.pathname === "/stats") {
      if (request.method !== "GET") return json(405, { error: "GET only" });
      if (!keyMatches(url.searchParams.get("key"), env.STATS_KEY)) return json(403, { error: "forbidden" });
      return json(200, await stats(env.DB));
    }
    return json(404, { error: "not found" });
  },

  // Daily cron (wrangler.toml): drop rows past the retention window.
  async scheduled(_event, env) {
    await prune(env.DB);
  },
};
