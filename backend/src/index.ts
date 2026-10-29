import { createChallenge, offlineUuid, verifyChallenge } from "./auth";
import type { Env } from "./env";
import {
  HEADERS as CERTIFICATE_HEADERS,
  fetchPlayerCertificateKeys,
  trustedKeys,
  verifyCertificate,
} from "./certificate";
import { monthKey, monthToDateEgressBytes } from "./usage";
import { isName } from "./validate";

export { Hub } from "./hub";

const HUB_NAME = "hub";
const SERVER_ID_PATTERN = /^[0-9a-f]{40}$/;
const KEYS_CACHE_MS = 60 * 60 * 1000;

let cachedKeys: { keys: string[] | null; until: number } | null = null;

/** The cron-refreshed Mojang keys, cached per isolate for an hour. */
async function refreshedKeys(env: Env, now: number): Promise<string[] | null> {
  if (cachedKeys === null || cachedKeys.until <= now) {
    cachedKeys = { keys: await hub(env).playerCertificateKeys(), until: now + KEYS_CACHE_MS };
  }
  return cachedKeys.keys;
}

/** Test hook. */
export function resetKeysCache(): void {
  cachedKeys = null;
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...headers },
  });
}

function error(code: string, status: number, retryAfter?: number): Response {
  return retryAfter === undefined
    ? json({ error: code }, status)
    : json({ error: code, retryAfter }, status, { "Retry-After": String(retryAfter) });
}

function hub(env: Env): DurableObjectStub<import("./hub").Hub> {
  return env.HUB.get(env.HUB.idFromName(HUB_NAME));
}

function clientIp(request: Request): string {
  return request.headers.get("CF-Connecting-IP") ?? "unknown";
}

async function challenge(request: Request, env: Env): Promise<Response> {
  const retryAfter = await hub(env).allowChallenge(clientIp(request));
  if (retryAfter > 0) return error("rate_limited", 429, retryAfter);
  return json(await createChallenge(env.AUTH_SECRET, Math.floor(Date.now() / 1000)));
}

async function connect(request: Request, env: Env): Promise<Response> {
  if (request.headers.get("Upgrade") !== "websocket") return error("upgrade_required", 426);
  const params = new URL(request.url).searchParams;
  const requestedName = params.get("name");
  if (!isName(requestedName)) return error("bad_request", 400);

  let uuid: string;
  let name: string;
  if (env.DEV_AUTH === "1" && params.get("dev") === "1") {
    uuid = await offlineUuid(requestedName);
    name = requestedName;
  } else {
    const serverId = params.get("serverId");
    if (serverId === null || !SERVER_ID_PATTERN.test(serverId)) return error("bad_request", 400);
    if (!(await verifyChallenge(env.AUTH_SECRET, serverId, Math.floor(Date.now() / 1000)))) {
      return error("bad_challenge", 401);
    }
    const now = Date.now();
    const result = await verifyCertificate(
      request.headers,
      serverId,
      now,
      trustedKeys(await refreshedKeys(env, now)),
    );
    if (!("uuid" in result)) return error("not_authenticated", 403);
    uuid = result.uuid;
    // Cosmetic only: never used to authorize anything (players see friends' names from their
    // own Friends List where they can).
    name = requestedName;
  }

  const headers = new Headers(request.headers);
  for (const header of Object.values(CERTIFICATE_HEADERS)) headers.delete(header);
  headers.set("X-OD-UUID", uuid);
  headers.set("X-OD-Name", name);
  headers.set("X-OD-IP", clientIp(request));
  return hub(env).fetch(new Request(request.url, { headers }));
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const path = new URL(request.url).pathname;
    try {
      if (path === "/v1/auth/challenge") {
        return request.method === "POST" ? await challenge(request, env) : error("method", 405);
      }
      if (path === "/v1/ws") {
        return request.method === "GET" ? await connect(request, env) : error("method", 405);
      }
      return error("not_found", 404);
    } catch {
      // Never leak internals (stack traces) to clients.
      return error("internal", 500);
    }
  },

  async scheduled(controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(
      (async () => {
        const keys = await fetchPlayerCertificateKeys();
        if (keys !== null) await hub(env).storePlayerCertificateKeys(keys);
      })(),
    );
    const { ANALYTICS_API_TOKEN: apiToken, ACCOUNT_ID: accountId, TURN_KEY_ID: keyId } = env;
    if (!apiToken || !accountId || !keyId) return;
    const now = new Date(controller.scheduledTime);
    ctx.waitUntil(
      (async () => {
        const bytes = await monthToDateEgressBytes({ accountId, apiToken, keyId }, now);
        if (bytes !== null) await hub(env).applyUsage(bytes, monthKey(now));
      })(),
    );
  },
} satisfies ExportedHandler<Env>;
