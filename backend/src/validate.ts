/**
 * Whitelist validation of every inbound WebSocket message: exact key sets, typed values, bounded
 * sizes. Anything else is rejected as a whole.
 */
export const MAX_MESSAGE_BYTES = 4096;
export const MAX_UUID_LIST = 1000;
export const MAX_CANDIDATES = 8;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const NAME = /^[A-Za-z0-9_]{1,16}$/;
const UFRAG = /^[A-Za-z0-9+/]{4,32}$/;
const PWD = /^[A-Za-z0-9+/]{22,64}$/;
const IPV4 = /^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$/;
const IPV6_CHARS = /^[0-9a-fA-F:.]{2,45}$/;
const MODES = ["friends", "invited"] as const;
const REJECT_REASONS = ["denied", "full", "blocked", "closed", "busy"] as const;
const CANDIDATE_KINDS = ["host", "srflx", "relay"] as const;

export type HostMode = (typeof MODES)[number];
export type RejectReason = (typeof REJECT_REASONS)[number];
export type CandidateKind = (typeof CANDIDATE_KINDS)[number];
export interface Candidate {
  k: CandidateKind;
  a: string;
  p: number;
}
export interface IceInfo {
  ufrag: string;
  pwd: string;
  candidates: Candidate[];
}

export type ClientMessage =
  | { t: "host.open" | "host.update"; mode: HostMode; allow: string[] }
  | { t: "host.close" }
  | { t: "directory.query"; uuids: string[] }
  | { t: "invite.send"; to: string }
  | ({ t: "join.request"; host: string } & IceInfo)
  | ({ t: "join.answer"; peer: string; conv: number } & IceInfo)
  | { t: "join.reject"; peer: string; reason: RejectReason }
  | { t: "turn.request" };

type Json = Record<string, unknown>;

export function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID.test(value);
}

export function isName(value: unknown): value is string {
  return typeof value === "string" && NAME.test(value);
}

function isIpLiteral(value: string): boolean {
  if (IPV4.test(value)) return true;
  // IPv6: allowed characters, at least two colons, at most one "::".
  if (!IPV6_CHARS.test(value)) return false;
  const colons = value.split(":").length - 1;
  return colons >= 2 && colons <= 7 && value.indexOf("::") === value.lastIndexOf("::");
}

function hasExactKeys(object: Json, keys: readonly string[]): boolean {
  const actual = Object.keys(object);
  return actual.length === keys.length && keys.every((key) => Object.hasOwn(object, key));
}

function isUuidList(value: unknown): value is string[] {
  return Array.isArray(value) && value.length <= MAX_UUID_LIST && value.every(isUuid);
}

function isOneOf<T extends string>(values: readonly T[], value: unknown): value is T {
  return typeof value === "string" && (values as readonly string[]).includes(value);
}

function isCandidate(value: unknown): value is Candidate {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const c = value as Json;
  return (
    hasExactKeys(c, ["k", "a", "p"]) &&
    isOneOf(CANDIDATE_KINDS, c.k) &&
    typeof c.a === "string" &&
    isIpLiteral(c.a) &&
    Number.isInteger(c.p) &&
    (c.p as number) >= 1 &&
    (c.p as number) <= 65535
  );
}

function isIceInfo(m: Json): boolean {
  return (
    typeof m.ufrag === "string" &&
    UFRAG.test(m.ufrag) &&
    typeof m.pwd === "string" &&
    PWD.test(m.pwd) &&
    Array.isArray(m.candidates) &&
    m.candidates.length <= MAX_CANDIDATES &&
    m.candidates.every(isCandidate)
  );
}

export function isConv(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 1 && (value as number) <= 0xffffffff;
}

/** Parses and validates one text frame; null when anything is off. */
export function parseClientMessage(raw: string): ClientMessage | null {
  if (raw.length > MAX_MESSAGE_BYTES) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return null;
  const m = parsed as Json;
  switch (m.t) {
    case "host.open":
    case "host.update":
      return hasExactKeys(m, ["t", "mode", "allow"]) && isOneOf(MODES, m.mode) && isUuidList(m.allow)
        ? (m as ClientMessage)
        : null;
    case "host.close":
    case "turn.request":
      return hasExactKeys(m, ["t"]) ? (m as ClientMessage) : null;
    case "directory.query":
      return hasExactKeys(m, ["t", "uuids"]) && isUuidList(m.uuids) ? (m as ClientMessage) : null;
    case "invite.send":
      return hasExactKeys(m, ["t", "to"]) && isUuid(m.to) ? (m as ClientMessage) : null;
    case "join.request":
      return hasExactKeys(m, ["t", "host", "ufrag", "pwd", "candidates"]) &&
        isUuid(m.host) &&
        isIceInfo(m)
        ? (m as ClientMessage)
        : null;
    case "join.answer":
      return hasExactKeys(m, ["t", "peer", "conv", "ufrag", "pwd", "candidates"]) &&
        isUuid(m.peer) &&
        isConv(m.conv) &&
        isIceInfo(m)
        ? (m as ClientMessage)
        : null;
    case "join.reject":
      return hasExactKeys(m, ["t", "peer", "reason"]) &&
        isUuid(m.peer) &&
        isOneOf(REJECT_REASONS, m.reason)
        ? (m as ClientMessage)
        : null;
    default:
      return null;
  }
}
