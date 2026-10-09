// Run with: npm test   (node --test, no dependencies)
// The SQL runs for real against SQLite (node:sqlite) through a small adapter
// with D1's prepare/bind/first/run/all/batch shape.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker, { validatePing, keyMatches, recordPing, prune, stats, utcDay, daysBefore } from "../src/worker.js";

function d1() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../schema.sql", import.meta.url), "utf8"));
  const statement = (sql, args = []) => ({
    bind: (...a) => statement(sql, a),
    // D1 hands back plain objects; node:sqlite's rows have a null prototype.
    first: async () => {
      const row = db.prepare(sql).get(...args);
      return row ? { ...row } : null;
    },
    run: async () => (db.prepare(sql).run(...args), { success: true }),
    all: async () => ({ results: db.prepare(sql).all(...args).map((r) => ({ ...r })) }),
    sql,
  });
  return {
    prepare: (sql) => statement(sql),
    batch: async (statements) => {
      const out = [];
      for (const s of statements) out.push(await s.all());
      return out;
    },
    raw: db,
  };
}

const ID_A = "6f1c2a0e-3b4d-4e5f-8a9b-0c1d2e3f4a5b";
const ID_B = "11111111-2222-4333-8444-555555555555";
const day = (s) => new Date(`${s}T12:00:00Z`);

test("validatePing accepts a real ping and nothing else", () => {
  assert.deepEqual(validatePing({ id: ID_A.toUpperCase(), appVersion: "1.0.3", androidSdk: 34, extra: "ignored" }), {
    id: ID_A,
    appVersion: "1.0.3",
    androidSdk: 34,
  });
  for (const bad of [
    null,
    [],
    "x",
    { id: "not-a-uuid", appVersion: "1.0", androidSdk: 34 },
    { id: ID_A, appVersion: "", androidSdk: 34 },
    { id: ID_A, appVersion: "1.0 <script>", androidSdk: 34 },
    { id: ID_A, appVersion: "x".repeat(21), androidSdk: 34 },
    { id: ID_A, appVersion: "1.0", androidSdk: "34" },
    { id: ID_A, appVersion: "1.0", androidSdk: 0 },
    { id: ID_A, appVersion: "1.0", androidSdk: 34.5 },
  ]) {
    assert.equal(validatePing(bad), null, JSON.stringify(bad));
  }
});

test("keyMatches is exact and refuses an unset key", () => {
  assert.equal(keyMatches("s3cret", "s3cret"), true);
  assert.equal(keyMatches("s3cre", "s3cret"), false);
  assert.equal(keyMatches("s3creT", "s3cret"), false);
  assert.equal(keyMatches(null, "s3cret"), false);
  assert.equal(keyMatches("", ""), false);
  assert.equal(keyMatches("anything", undefined), false);
});

test("one row per id per UTC day, and installs count once", async () => {
  const db = d1();
  const ping = { id: ID_A, appVersion: "1.0.2", androidSdk: 34 };
  await recordPing(db, ping, day("2026-10-01"));
  await recordPing(db, { ...ping, appVersion: "1.0.3" }, day("2026-10-01")); // same day: upsert
  await recordPing(db, { ...ping, appVersion: "1.0.3" }, day("2026-10-02"));
  await recordPing(db, { id: ID_B, appVersion: "1.0.3", androidSdk: 30 }, day("2026-10-02"));
  const rows = db.raw.prepare("SELECT id, day, app_version FROM pings ORDER BY day, id").all();
  assert.equal(rows.length, 3);
  assert.equal(rows.find((r) => r.id === ID_A && r.day === "2026-10-01").app_version, "1.0.3");
  assert.equal(db.raw.prepare("SELECT value FROM counters WHERE name='installs'").get().value, 2);
});

test("stats: daily actives, monthly actives, versions by latest report", async () => {
  const db = d1();
  await recordPing(db, { id: ID_A, appVersion: "1.0.2", androidSdk: 34 }, day("2026-10-01"));
  await recordPing(db, { id: ID_A, appVersion: "1.0.3", androidSdk: 34 }, day("2026-10-05"));
  await recordPing(db, { id: ID_B, appVersion: "1.0.2", androidSdk: 30 }, day("2026-10-05"));
  const s = await stats(db, day("2026-10-05"));
  assert.deepEqual(s.dailyActiveUsers, [
    { day: "2026-10-01", users: 1 },
    { day: "2026-10-05", users: 2 },
  ]);
  assert.equal(s.monthlyActiveUsers, 2);
  assert.equal(s.activeInstallsLast90Days, 2);
  assert.equal(s.totalUniqueInstalls, 2);
  assert.deepEqual(
    s.versionsLast30Days.map((v) => [v.version, v.users]).sort(),
    [["1.0.2", 1], ["1.0.3", 1]],
  );
});

test("prune drops rows older than 90 days", async () => {
  const db = d1();
  await recordPing(db, { id: ID_A, appVersion: "1.0.0", androidSdk: 34 }, day("2026-06-01"));
  await recordPing(db, { id: ID_B, appVersion: "1.0.3", androidSdk: 34 }, day("2026-10-01"));
  await prune(db, day("2026-10-01"));
  assert.deepEqual(db.raw.prepare("SELECT id FROM pings").all().map((r) => r.id), [ID_B]);
  assert.equal(daysBefore(day("2026-10-01"), 90), "2026-07-03");
  assert.equal(utcDay(new Date("2026-10-01T23:59:59Z")), "2026-10-01");
});

test("HTTP: ping, bad ping, stats key", async () => {
  const env = { DB: d1(), STATS_KEY: "k" };
  const post = (body) =>
    worker.fetch(new Request("https://w.example/ping", { method: "POST", body: JSON.stringify(body) }), env);
  assert.equal((await post({ id: ID_A, appVersion: "1.0.3", androidSdk: 34 })).status, 204);
  assert.equal((await post({ id: "x", appVersion: "1.0.3", androidSdk: 34 })).status, 400);
  assert.equal((await worker.fetch(new Request("https://w.example/ping"), env)).status, 405);
  assert.equal((await worker.fetch(new Request("https://w.example/stats?key=nope"), env)).status, 403);
  const ok = await worker.fetch(new Request("https://w.example/stats?key=k"), env);
  assert.equal(ok.status, 200);
  assert.equal((await ok.json()).monthlyActiveUsers, 1);
  assert.equal((await worker.fetch(new Request("https://w.example/"), env)).status, 404);
  // Nothing about the caller beyond the ping fields is stored.
  const columns = env.DB.raw.prepare("PRAGMA table_info(pings)").all().map((c) => c.name);
  assert.deepEqual(columns, ["id", "day", "app_version", "android_sdk"]);
});
