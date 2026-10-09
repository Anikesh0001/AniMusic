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
