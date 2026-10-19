/**
 * AniMusic's Worker: the anonymous usage count (stats.js) and accounts
 * (accounts.js). Only the handler is exported here: the Workers runtime treats
 * every named export of the main module as an entrypoint and refuses anything
 * else, so the helpers and their tests live in those modules.
 */
import { validatePing, keyMatches, recordPing, prune, stats } from "./stats.js";
import { handleAccount, pruneAccounts } from "./accounts.js";

const MAX_BODY_BYTES = 512;

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
    if (url.pathname === "/account" || url.pathname.startsWith("/account/")) {
      const handled = await handleAccount(request, env.DB);
      if (handled) return json(handled[0], handled[1]);
    }
    return json(404, { error: "not found" });
  },

  // Daily cron (wrangler.toml): drop rows past the retention window.
  async scheduled(_event, env) {
    await prune(env.DB);
    await pruneAccounts(env.DB);
  },
};
