/**
 * Single door for every outbound HTTP call (Mojang, Cloudflare APIs), so tests can replace it and
 * never reach the real services.
 */
export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

const realFetch: FetchLike = (input, init) => fetch(input, init);
let current: FetchLike = realFetch;

export function outboundFetch(input: string, init?: RequestInit): Promise<Response> {
  return current(input, init);
}

/** Test hook: replaces outbound HTTP; pass null to restore the real fetch. */
export function setOutboundFetch(replacement: FetchLike | null): void {
  current = replacement ?? realFetch;
}
