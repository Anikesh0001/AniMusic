// Run with: npm test   (node --test, no dependencies)
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker from "../src/worker.js";
import { MAX_FAILURES, validateRegister } from "../src/accounts.js";

function d1() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../schema.sql", import.meta.url), "utf8"));
  const statement = (sql, args = []) => ({
    bind: (...a) => statement(sql, a),
    first: async () => {
      const row = db.prepare(sql).get(...args);
      return row ? { ...row } : null;
    },
    run: async () => (db.prepare(sql).run(...args), { success: true }),
    all: async () => ({ results: db.prepare(sql).all(...args).map((r) => ({ ...r })) }),
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

function call(env, method, path, body, token) {
  const headers = { "content-type": "application/json" };
  if (token) headers.authorization = `Bearer ${token}`;
  const init = { method, headers };
  if (body !== undefined) init.body = JSON.stringify(body);
  return worker.fetch(new Request(`https://w.example${path}`, init), env).then(async (r) => ({
    status: r.status,
    body: r.status === 204 ? null : await r.json(),
  }));
}

test("validateRegister checks email and password, defaults the name", () => {
  assert.deepEqual(validateRegister({ email: " A@B.co ", password: "secret" }), {
    email: "a@b.co",
    password: "secret",
    name: "a",
  });
  assert.equal(validateRegister({ email: "nope", password: "secret" }).error, "invalid_email");
  assert.equal(validateRegister({ email: "a@b.co", password: "123" }).error, "password_too_short");
});

test("register, log in again later, sync, log out, delete", async () => {
  const env = { DB: d1() };
  const reg = await call(env, "POST", "/account/register", { email: "Me@Mail.com", password: "hunter22", name: "Ani" });
  assert.equal(reg.status, 200);
  assert.equal(reg.body.account.email, "me@mail.com");
  assert.match(reg.body.token, /^[0-9a-f]{64}$/);

  // Nothing secret is stored in the clear.
  const row = env.DB.raw.prepare("SELECT * FROM accounts").get();
  assert.notEqual(row.pw_hash, "hunter22");
  const session = env.DB.raw.prepare("SELECT token_hash FROM sessions").get();
  assert.notEqual(session.token_hash, reg.body.token);

  assert.equal((await call(env, "POST", "/account/register", { email: "me@mail.com", password: "another" })).status, 409);
  assert.equal((await call(env, "POST", "/account/login", { email: "me@mail.com", password: "wrong!" })).status, 401);

  const again = await call(env, "POST", "/account/login", { email: " ME@mail.com", password: "hunter22" });
  assert.equal(again.status, 200);
  const token = again.body.token;
  assert.equal((await call(env, "GET", "/account/me", undefined, token)).body.account.name, "Ani");

  // Sync: empty, write, conflicting write refused, read back.
  assert.deepEqual((await call(env, "GET", "/account/sync", undefined, token)).body, { data: null, updatedAt: 0 });
  const put = await call(env, "PUT", "/account/sync", { data: { playlists: [1] }, baseUpdatedAt: 0 }, token);
  assert.equal(put.status, 200);
  const stale = await call(env, "PUT", "/account/sync", { data: { playlists: [] }, baseUpdatedAt: 0 }, token);
  assert.equal(stale.status, 409);
  const got = await call(env, "GET", "/account/sync", undefined, reg.body.token);
  assert.deepEqual(got.body, { data: { playlists: [1] }, updatedAt: put.body.updatedAt });

  assert.equal((await call(env, "POST", "/account/logout", undefined, token)).status, 204);
  assert.equal((await call(env, "GET", "/account/me", undefined, token)).status, 401);

  assert.equal((await call(env, "DELETE", "/account", { password: "wrong!" }, reg.body.token)).status, 401);
  assert.equal((await call(env, "DELETE", "/account", { password: "hunter22" }, reg.body.token)).status, 204);
  assert.equal((await call(env, "POST", "/account/login", { email: "me@mail.com", password: "hunter22" })).status, 401);
  assert.equal(env.DB.raw.prepare("SELECT COUNT(*) AS n FROM sync").get().n, 0);
});

test("too many wrong passwords lock login for a while", async () => {
  const env = { DB: d1() };
  await call(env, "POST", "/account/register", { email: "x@y.io", password: "rightpass" });
  for (let i = 0; i < MAX_FAILURES; i++) {
    await call(env, "POST", "/account/login", { email: "x@y.io", password: "bad" });
  }
  assert.equal((await call(env, "POST", "/account/login", { email: "x@y.io", password: "rightpass" })).status, 429);
});

test("requests without a valid token are refused", async () => {
  const env = { DB: d1() };
  assert.equal((await call(env, "GET", "/account/sync")).status, 401);
  assert.equal((await call(env, "GET", "/account/sync", undefined, "a".repeat(64))).status, 401);
});
