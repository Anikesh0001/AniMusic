-- AniMusic anonymous active-user count (Cloudflare D1).
-- One row per random install id per UTC day; nothing else about the caller.
CREATE TABLE IF NOT EXISTS pings (
  id          TEXT    NOT NULL,  -- random UUID made by the app on first launch
  day         TEXT    NOT NULL,  -- UTC date, YYYY-MM-DD
  app_version TEXT    NOT NULL,
  android_sdk INTEGER NOT NULL,
  PRIMARY KEY (id, day)
);
CREATE INDEX IF NOT EXISTS pings_day ON pings (day);

-- Lifetime counters (just "installs" today).
CREATE TABLE IF NOT EXISTS counters (
  name  TEXT    PRIMARY KEY,
  value INTEGER NOT NULL
);

-- AniMusic accounts (email + password, no verification). Passwords are stored
-- only as PBKDF2-SHA256 hashes; session tokens only as SHA-256 hashes.
CREATE TABLE IF NOT EXISTS accounts (
  id         TEXT    PRIMARY KEY,
  email      TEXT    NOT NULL UNIQUE,
  name       TEXT    NOT NULL,
  pw_hash    TEXT    NOT NULL,
  pw_salt    TEXT    NOT NULL,
  pw_iter    INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT    PRIMARY KEY,
  account_id TEXT    NOT NULL,
  created_at INTEGER NOT NULL,
  last_used  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_account ON sessions (account_id);
-- One JSON blob of synced on-device data per account.
CREATE TABLE IF NOT EXISTS sync (
  account_id TEXT    PRIMARY KEY,
  data       TEXT    NOT NULL,
  updated_at INTEGER NOT NULL
);
-- Recent wrong passwords, to slow down guessing.
CREATE TABLE IF NOT EXISTS login_failures (
  email TEXT    NOT NULL,
  at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS login_failures_email ON login_failures (email, at);
