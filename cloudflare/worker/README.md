# AniMusic anonymous user count (Cloudflare Worker)

A tiny Worker plus a D1 (SQLite) database that counts how many AniMusic installs open the app each day. Cloudflare's free plan is enough: 100,000 requests a day, and D1 has 5 GB and 100,000 writes a day. AniMusic sends at most one request per install per day.

**What it stores:** one row per install per UTC day, with `id` (a random UUID the app makes, tied to nothing), the date, the app version and the Android SDK level. It also keeps one lifetime counter of new installs. It never stores IP addresses: the Worker doesn't read them, and request logging is off (`[observability] enabled = false`). Rows older than 90 days are deleted every night.

**Endpoints**

- `POST /ping` with `{"id": "<uuid>", "appVersion": "1.0.3", "androidSdk": 34}` returns `204`. A bad body returns `400`.
- `GET /stats?key=<STATS_KEY>` returns daily active users for the last 30 days, monthly actives, actives in the last 90 days, total unique installs, and a version breakdown. A wrong key returns `403`.

## One-time setup

You need Node.js (`node --version` should print 18 or newer). Run every command from this folder:

```bash
cd cloudflare/worker
```

### 1. Create a free Cloudflare account

Sign up at <https://dash.cloudflare.com/sign-up> and verify your email. No credit card or domain is needed.

### 2. Log in from the terminal

```bash
npx wrangler login
```

A browser tab opens. Click **Allow**. (`npx` downloads wrangler, Cloudflare's CLI, the first time.)

### 3. Create the database

```bash
npx wrangler d1 create animusic-stats
```

It prints a block containing `database_id = "…"`. Copy that id into `wrangler.toml`, replacing `REPLACE_WITH_YOUR_DATABASE_ID`. The id isn't a secret, so committing it is fine.

Then create the tables:

```bash
npx wrangler d1 execute animusic-stats --remote --file=schema.sql
```

### 4. Deploy the Worker

```bash
npx wrangler deploy
```

The last lines show your Worker's URL, for example:

```
https://animusic-stats.<your-subdomain>.workers.dev
```

The first time, Cloudflare may ask you to choose a `workers.dev` subdomain. Any name works.

### 5. Set the stats password (a Worker secret)

Make a long random password and store it as a secret. It lives only in Cloudflare, never in this repo:

```bash
openssl rand -hex 24          # copy the output, and keep it in your password manager
npx wrangler secret put STATS_KEY
# paste the password when it asks
```

### 6. Check it works

```bash
URL=https://animusic-stats.<your-subdomain>.workers.dev
curl -i -X POST "$URL/ping" -H 'content-type: application/json' \
     -d '{"id":"00000000-0000-4000-8000-000000000000","appVersion":"test","androidSdk":34}'
# HTTP/2 204
curl "$URL/stats?key=<your STATS_KEY>"
# {"monthlyActiveUsers": 1, ...}
```

To clear that test row:

```bash
npx wrangler d1 execute animusic-stats --remote --command "DELETE FROM pings WHERE app_version = 'test'"
```

(The lifetime `totalUniqueInstalls` counter keeps the 1. Reset it with `UPDATE counters SET value = 0` before real users arrive, if you like.)

### 7. Turn it on in the app

Add the ping URL to `local.properties` in the repo root. That file is gitignored, and the URL is only read when building:

```
ANIMUSIC_STATS_URL=https://animusic-stats.<your-subdomain>.workers.dev/ping
```

Then build or release as usual. Builds without this line send nothing (the feature is off).

## Viewing your stats

Open this in a browser (bookmark it, and don't share it):

```
https://animusic-stats.<your-subdomain>.workers.dev/stats?key=<your STATS_KEY>
```

| Field | Meaning |
|---|---|
| `dailyActiveUsers` | One `{day, users}` entry per UTC day in the last 30 days: installs that opened AniMusic that day. |
| `monthlyActiveUsers` | Distinct installs active in the last 30 days. |
| `activeInstallsLast90Days` | Distinct installs active in the last 90 days. |
| `totalUniqueInstalls` | Lifetime count of new install ids. An install idle for over 90 days is forgotten, so it's counted again if it returns. |
| `versionsLast30Days` | How many of the last 30 days' installs run each version, by the version each one reported most recently. |

Raw SQL works too, for example `npx wrangler d1 execute animusic-stats --remote --command "SELECT day, COUNT(*) FROM pings GROUP BY day"`.

## Updating the Worker

Edit `src/worker.js`, run `npm test` (which uses Node's built-in test runner and SQLite, no install needed), then `npx wrangler deploy`.

## Limits

Anyone who knows the `/ping` URL can send made-up ids, so treat the numbers as an honest estimate, not proof. Ids that aren't UUIDs, oversized bodies and odd version strings are rejected.
