# Deploying the Open Doors backend (Cloudflare Workers, free plan)

The backend is a single Cloudflare Worker with one SQLite Durable Object ("Hub"). It only
introduces players to each other: login, who has a world open to whom, invites, and the addresses
the two games need to connect. The game itself travels directly between the two computers; the
backend never carries gameplay traffic.

There is no relay by default. When a direct connection is impossible (some mobile, company or
campus networks, VPNs) the friend sees "Could not connect". An optional relay (Cloudflare TURN)
is described at the end; Cloudflare only enables it on accounts with a payment card.

You need Node.js. Run every command inside this `backend/` folder.

## 1. Cloudflare account

1. Sign up at <https://dash.cloudflare.com/sign-up> (free; no domain or card needed).
2. Confirm your email.

## 2. Install and log in

```sh
npm ci
npx wrangler login
```

`wrangler login` opens the browser: press **Allow**.

## 3. Login secret

Create a random key that signs login challenges and store it as a secret:

```sh
openssl rand -hex 32
npx wrangler secret put AUTH_SECRET   # paste the value from the line above
```

(The first time, wrangler may ask whether to create the Worker: answer yes.)

Keep `DEV_AUTH` set to `"0"` in `wrangler.jsonc`: with `"1"` anyone could log in under any name
(local tests only).

## 4. Deploy

```sh
npm test
npx wrangler deploy
```

It prints an address such as `https://open-doors.YOUR-SUBDOMAIN.workers.dev`. Copy it.

## 5. Point the mod at it

In the mod's `gradle.properties` set the address (the `https` one, no trailing slash; the mod
derives the WebSocket address itself):

```properties
backend_url=https://open-doors.YOUR-SUBDOMAIN.workers.dev
```

Rebuild the mod (`./gradlew build`). Each player can also change it in `config/opendoors.json` or
in the mod's settings screen.

## Limits and cost

- Workers free plan: 100,000 requests per day, SQLite Durable Objects included. A play session
  uses a handful of requests (login and short messages), far below that.
- A cron runs every 15 minutes to refresh Mojang's public keys (see below).

## Test locally (nothing published)

```sh
cp .dev.vars.example .dev.vars
npx wrangler dev --var DEV_AUTH:1
node scripts/dev-invite-check.mjs
```

## Mojang keys (login)

The backend checks each player's identity with their Mojang-signed player certificate (the same
one the game uses to sign chat); it does not call Mojang on every connection. Mojang's public keys
are embedded in `src/mojang-keys.ts` and the cron refreshes them from
`https://api.minecraftservices.com/publickeys` when it can. If Mojang ever rotates them and the
cron cannot fetch them, update them by hand: open that address in a browser, copy the `publicKey`
values of `playerCertificateKeys` into `src/mojang-keys.ts` and run `npx wrangler deploy` again.

## Optional: relay (Cloudflare TURN, needs a card on the account)

With a TURN key configured, the backend hands out short-lived relay credentials when a direct
connection fails. Without these secrets it simply offers no relay.

1. In the Cloudflare dashboard: **Realtime** → **TURN Server** (may show as *Calls* → *TURN
   Keys*) → **Create**. Save the **Turn Token ID** and the **API Token** (shown only once).
2. Profile icon → **My Profile** → **API Tokens** → **Create Token** → **Create Custom Token**,
   permission **Account** → **Account Analytics** → **Read** for your account. Copy the token.
3. Your **Account ID** is on the account home page (right column) or in the dashboard URL
   (`dash.cloudflare.com/<ACCOUNT_ID>/...`).
4. Store them and deploy again:

   ```sh
   npx wrangler secret put TURN_KEY_ID          # Turn Token ID from step 1
   npx wrangler secret put TURN_KEY_API_TOKEN   # API Token from step 1
   npx wrangler secret put ANALYTICS_API_TOKEN  # token from step 2
   npx wrangler secret put ACCOUNT_ID           # Account ID from step 3
   npx wrangler deploy
   ```

Cloudflare includes 1,000 GB of relay traffic per month. Every 15 minutes the cron reads this
month's usage and the backend stops at **900 GB** (`TURN_MONTHLY_CAP_BYTES`): it hands out no more
relay credentials, revokes the active ones, and players see "Could not connect (monthly limit)".
Direct connections keep working. The count resets on the 1st of each month. Usage is shown in
**Realtime** → **TURN Server** → *Analytics*.
