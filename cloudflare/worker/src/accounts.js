/**
 * AniMusic accounts: email + password, no verification, and one synced blob of
 * the user's on-device data (playlists, likes, settings) per account.
 *
 *   POST   /account/register  {"email", "password", "name"}  -> {token, account}
 *   POST   /account/login     {"email", "password"}          -> {token, account}
 *   GET    /account/me        Bearer                         -> {account}
 *   POST   /account/logout    Bearer                         -> 204
 *   DELETE /account           Bearer + {"password"}          -> 204 (account, sessions, data)
 *   GET    /account/sync      Bearer                         -> {data, updatedAt}
 *   PUT    /account/sync      Bearer + {"data", "baseUpdatedAt"} -> {updatedAt}
 *
 * Passwords are never stored: only a PBKDF2-SHA256 hash with a per-account
 * salt. Session tokens are random; only their SHA-256 is stored, so a leaked
 * database holds nothing that can be replayed. No email is ever sent.
 */

export const PBKDF2_ITERATIONS = 100_000; // the Workers runtime's maximum
export const MIN_PASSWORD = 6;
const MAX_PASSWORD = 128;
const MAX_NAME = 40;
export const MAX_SYNC_BYTES = 1_000_000;
const MAX_AUTH_BODY = 2_048;
/** Wrong passwords allowed per email in FAILURE_WINDOW_MS before login is refused. */
export const MAX_FAILURES = 10;
const FAILURE_WINDOW_MS = 15 * 60_000;

const EMAIL = /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,}$/;

const enc = new TextEncoder();
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
const fromHex = (s) => new Uint8Array(s.match(/../g).map((h) => parseInt(h, 16)));
const randomHex = (bytes) => hex(crypto.getRandomValues(new Uint8Array(bytes)));

export function normalizeEmail(email) {
  return typeof email === "string" ? email.trim().toLowerCase() : "";
}

/** A register body made safe, or an error message. */
export function validateRegister(body) {
  if (!body || typeof body !== "object") return { error: "bad request" };
  const email = normalizeEmail(body.email);
  const password = typeof body.password === "string" ? body.password : "";
  const name = typeof body.name === "string" ? body.name.trim().slice(0, MAX_NAME) : "";
  if (!EMAIL.test(email) || email.length > 254) return { error: "invalid_email" };
  if (password.length < MIN_PASSWORD) return { error: "password_too_short" };
  if (password.length > MAX_PASSWORD) return { error: "password_too_long" };
  return { email, password, name: name || email.split("@")[0].slice(0, MAX_NAME) };
}

export async function hashPassword(password, saltHex, iterations = PBKDF2_ITERATIONS) {
  const key = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: fromHex(saltHex), iterations },
    key,
    256,
  );
  return hex(bits);
}

async function sha256(text) {
  return hex(await crypto.subtle.digest("SHA-256", enc.encode(text)));
}

/** Constant-time comparison of two hex strings. */
function sameHex(a, b) {
  let diff = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  return diff === 0;
}

const publicAccount = (row) => ({ id: row.id, email: row.email, name: row.name });

async function newSession(db, accountId, now) {
  const token = randomHex(32);
  await db
    .prepare("INSERT INTO sessions (token_hash, account_id, created_at, last_used) VALUES (?1, ?2, ?3, ?3)")
    .bind(await sha256(token), accountId, now)
    .run();
  return token;
}

export async function register(db, body, now = Date.now()) {
  const v = validateRegister(body);
  if (v.error) return [400, { error: v.error }];
  const taken = await db.prepare("SELECT 1 FROM accounts WHERE email = ?1").bind(v.email).first();
  if (taken) return [409, { error: "email_taken" }];
  const salt = randomHex(16);
  const account = { id: crypto.randomUUID(), email: v.email, name: v.name };
  await db
    .prepare(
      `INSERT INTO accounts (id, email, name, pw_hash, pw_salt, pw_iter, created_at)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)`,
    )
    .bind(account.id, account.email, account.name, await hashPassword(v.password, salt), salt, PBKDF2_ITERATIONS, now)
    .run();
  return [200, { token: await newSession(db, account.id, now), account }];
}

async function checkPassword(db, email, password, now) {
  const since = now - FAILURE_WINDOW_MS;
  const failures = await db
    .prepare("SELECT COUNT(*) AS n FROM login_failures WHERE email = ?1 AND at >= ?2")
    .bind(email, since)
    .first();
  if ((failures?.n ?? 0) >= MAX_FAILURES) return { status: 429, error: "too_many_attempts" };
  const row = await db.prepare("SELECT * FROM accounts WHERE email = ?1").bind(email).first();
  const ok = row && sameHex(await hashPassword(password, row.pw_salt, row.pw_iter), row.pw_hash);
  if (!ok) {
    await db.prepare("INSERT INTO login_failures (email, at) VALUES (?1, ?2)").bind(email, now).run();
    return { status: 401, error: "wrong_email_or_password" };
  }
  return { row };
}

export async function login(db, body, now = Date.now()) {
  const email = normalizeEmail(body?.email);
  const password = typeof body?.password === "string" ? body.password : "";
  if (!email || !password || password.length > MAX_PASSWORD) return [401, { error: "wrong_email_or_password" }];
  const result = await checkPassword(db, email, password, now);
  if (result.error) return [result.status, { error: result.error }];
  await db.prepare("DELETE FROM login_failures WHERE email = ?1").bind(email).run();
  return [200, { token: await newSession(db, result.row.id, now), account: publicAccount(result.row) }];
}

/** The account behind a request's bearer token, or null. */
export async function authenticate(db, request, now = Date.now()) {
  const header = request.headers.get("authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!/^[0-9a-f]{64}$/.test(token)) return null;
  const tokenHash = await sha256(token);
  const row = await db
    .prepare(
      `SELECT a.id, a.email, a.name FROM sessions s JOIN accounts a ON a.id = s.account_id
       WHERE s.token_hash = ?1`,
    )
    .bind(tokenHash)
    .first();
  if (!row) return null;
  await db.prepare("UPDATE sessions SET last_used = ?2 WHERE token_hash = ?1").bind(tokenHash, now).run();
  return { ...publicAccount(row), tokenHash };
}

export async function logout(db, account) {
  await db.prepare("DELETE FROM sessions WHERE token_hash = ?1").bind(account.tokenHash).run();
  return [204, null];
}

export async function deleteAccount(db, account, body, now = Date.now()) {
  const password = typeof body?.password === "string" ? body.password : "";
  const result = await checkPassword(db, account.email, password, now);
  if (result.error) return [result.status, { error: result.error }];
  await db.batch([
    db.prepare("DELETE FROM sync WHERE account_id = ?1").bind(account.id),
    db.prepare("DELETE FROM sessions WHERE account_id = ?1").bind(account.id),
    db.prepare("DELETE FROM login_failures WHERE email = ?1").bind(account.email),
    db.prepare("DELETE FROM accounts WHERE id = ?1").bind(account.id),
  ]);
  return [204, null];
}

export async function getSync(db, account) {
  const row = await db.prepare("SELECT data, updated_at FROM sync WHERE account_id = ?1").bind(account.id).first();
  return [200, { data: row ? JSON.parse(row.data) : null, updatedAt: row?.updated_at ?? 0 }];
}

/**
 * Replaces the account's data. [baseUpdatedAt] is the version the device last
 * saw; if another device has written since, the write is refused with 409 and
 * the device must pull, merge and try again, so nothing is silently lost.
 */
export async function putSync(db, account, body, now = Date.now()) {
  if (!body || typeof body !== "object" || body.data === null || typeof body.data !== "object") {
    return [400, { error: "bad request" }];
  }
  const text = JSON.stringify(body.data);
  if (enc.encode(text).length > MAX_SYNC_BYTES) return [413, { error: "too_large" }];
  const current = await db.prepare("SELECT updated_at FROM sync WHERE account_id = ?1").bind(account.id).first();
  const base = Number.isInteger(body.baseUpdatedAt) ? body.baseUpdatedAt : 0;
  if (current && current.updated_at !== base) return [409, { error: "conflict", updatedAt: current.updated_at }];
  const updatedAt = Math.max(now, (current?.updated_at ?? 0) + 1);
  await db
    .prepare(
      `INSERT INTO sync (account_id, data, updated_at) VALUES (?1, ?2, ?3)
       ON CONFLICT (account_id) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at`,
    )
    .bind(account.id, text, updatedAt)
    .run();
  return [200, { updatedAt }];
}

/** Drops old failed-login rows and sessions unused for a year. */
export async function pruneAccounts(db, now = Date.now()) {
  await db.batch([
    db.prepare("DELETE FROM login_failures WHERE at < ?1").bind(now - FAILURE_WINDOW_MS),
    db.prepare("DELETE FROM sessions WHERE last_used < ?1").bind(now - 365 * 86_400_000),
  ]);
}

async function readBody(request, limit) {
  const length = Number(request.headers.get("content-length") || "0");
  if (length > limit) return undefined;
  const text = await request.text();
  if (enc.encode(text).length > limit) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

/** Routes /account/*; returns [status, body] or null when the path isn't ours. */
export async function handleAccount(request, db) {
  const { pathname } = new URL(request.url);
  const method = request.method;
  if (pathname === "/account/register" && method === "POST") {
    return register(db, await readBody(request, MAX_AUTH_BODY));
  }
  if (pathname === "/account/login" && method === "POST") {
    return login(db, await readBody(request, MAX_AUTH_BODY));
  }
  if (!["/account", "/account/me", "/account/logout", "/account/sync"].includes(pathname)) return null;
  const account = await authenticate(db, request);
  if (!account) return [401, { error: "signed_out" }];
  if (pathname === "/account/me" && method === "GET") return [200, { account: publicAccount(account) }];
  if (pathname === "/account/logout" && method === "POST") return logout(db, account);
  if (pathname === "/account" && method === "DELETE") {
    return deleteAccount(db, account, await readBody(request, MAX_AUTH_BODY));
  }
  if (pathname === "/account/sync" && method === "GET") return getSync(db, account);
  if (pathname === "/account/sync" && method === "PUT") {
    const body = await readBody(request, MAX_SYNC_BYTES + 1024);
    if (body === undefined) return [413, { error: "too_large" }];
    return putSync(db, account, body);
  }
  return [405, { error: "method not allowed" }];
}
