import { outboundFetch } from "./outbound";

/**
 * Month-to-date TURN egress from Cloudflare GraphQL Analytics. Dataset and fields confirmed on
 * 2026-10-21 in https://developers.cloudflare.com/realtime/turn/analytics/ :
 * viewer.accounts(filter: {accountTag}).callsTurnUsageAdaptiveGroups(filter: {date_geq, date_leq,
 * keyId}) { sum { egressBytes } } (egress = bytes sent from TURN servers to clients, the billed
 * direction). The token needs "Account Analytics: Read".
 */
export const GRAPHQL_URL = "https://api.cloudflare.com/client/v4/graphql";

const QUERY = `query TurnEgress($accountTag: string!, $from: Date!, $to: Date!, $keyId: string!) {
  viewer {
    accounts(filter: { accountTag: $accountTag }) {
      callsTurnUsageAdaptiveGroups(
        limit: 1
        filter: { date_geq: $from, date_leq: $to, keyId: $keyId }
      ) {
        sum { egressBytes }
      }
    }
  }
}`;

export interface UsageQuery {
  accountId: string;
  apiToken: string;
  keyId: string;
}

/** "YYYY-MM" in UTC. */
export function monthKey(now: Date): string {
  return now.toISOString().slice(0, 7);
}

/** Egress bytes since the first day of the current UTC month; null when unavailable. */
export async function monthToDateEgressBytes(query: UsageQuery, now: Date): Promise<number | null> {
  const to = now.toISOString().slice(0, 10);
  const from = `${monthKey(now)}-01`;
  let response: Response;
  try {
    response = await outboundFetch(GRAPHQL_URL, {
      method: "POST",
      headers: { Authorization: `Bearer ${query.apiToken}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        query: QUERY,
        variables: { accountTag: query.accountId, from, to, keyId: query.keyId },
      }),
    });
  } catch {
    return null;
  }
  if (!response.ok) return null;
  const body = (await response.json().catch(() => null)) as {
    data?: {
      viewer?: {
        accounts?: { callsTurnUsageAdaptiveGroups?: { sum?: { egressBytes?: unknown } }[] }[];
      };
    };
    errors?: unknown[] | null;
  } | null;
  if (body === null || (Array.isArray(body.errors) && body.errors.length > 0)) return null;
  const groups = body.data?.viewer?.accounts?.[0]?.callsTurnUsageAdaptiveGroups;
  if (!Array.isArray(groups)) return null;
  let total = 0;
  for (const group of groups) {
    const bytes = group.sum?.egressBytes;
    if (typeof bytes === "number" && Number.isFinite(bytes) && bytes >= 0) total += bytes;
  }
  return total;
}
