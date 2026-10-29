import { outboundFetch } from "./outbound";

/**
 * Cloudflare Realtime TURN credentials
 * (https://developers.cloudflare.com/realtime/turn/generate-credentials/).
 */
const API = "https://rtc.live.cloudflare.com/v1/turn/keys";
export const TURN_TTL_SECONDS = 3600;
const UDP_TURN_URL = /^turn:[A-Za-z0-9.-]+:(3478|53)\?transport=udp$/;

export interface TurnCredentials {
  urls: string[];
  username: string;
  credential: string;
}

export interface TurnKey {
  keyId: string;
  apiToken: string;
}

/** Mints short-lived credentials; keeps only plain UDP TURN URLs (the mod speaks TURN over UDP). */
export async function mintTurnCredentials(key: TurnKey): Promise<TurnCredentials | null> {
  let response: Response;
  try {
    response = await outboundFetch(
      `${API}/${encodeURIComponent(key.keyId)}/credentials/generate-ice-servers`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${key.apiToken}`, "Content-Type": "application/json" },
        body: JSON.stringify({ ttl: TURN_TTL_SECONDS }),
      },
    );
  } catch {
    return null;
  }
  if (!response.ok) return null;
  const body = (await response.json().catch(() => null)) as {
    iceServers?: { urls?: unknown; username?: unknown; credential?: unknown }[];
  } | null;
  for (const server of body?.iceServers ?? []) {
    if (typeof server.username !== "string" || typeof server.credential !== "string") continue;
    const urls = (Array.isArray(server.urls) ? server.urls : [server.urls]).filter(
      (url): url is string => typeof url === "string" && UDP_TURN_URL.test(url),
    );
    if (urls.length > 0) {
      return { urls, username: server.username, credential: server.credential };
    }
  }
  return null;
}

/** Revokes one credential; true on success (204). */
export async function revokeTurnCredential(key: TurnKey, username: string): Promise<boolean> {
  try {
    const response = await outboundFetch(
      `${API}/${encodeURIComponent(key.keyId)}/credentials/${encodeURIComponent(username)}/revoke`,
      { method: "POST", headers: { Authorization: `Bearer ${key.apiToken}` } },
    );
    return response.ok;
  } catch {
    return false;
  }
}
