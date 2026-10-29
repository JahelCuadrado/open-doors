import { DurableObject } from "cloudflare:workers";
import type { Env } from "./env";
import { type Bucket, WindowLimiter, fullBucket, take } from "./ratelimit";
import {
  type TurnKey,
  TURN_TTL_SECONDS,
  mintTurnCredentials,
  revokeTurnCredential,
} from "./turn";
import { monthKey } from "./usage";
import { type ClientMessage, isName, isUuid, parseClientMessage } from "./validate";

const PENDING_JOIN_MS = 60_000;
const MAX_SOCKETS_PER_IP = 4;
const BAD_MESSAGES_BEFORE_CLOSE = 5;
const RATE_LIMITED_BEFORE_CLOSE = 10;
const RATE_LIMIT_WINDOW_MS = 60_000;
const CHALLENGES_PER_IP = 10;
const CHALLENGE_WINDOW_MS = 60_000;
const CLOSE_POLICY_VIOLATION = 1008;
const CLOSE_REPLACED = 4000;
const IP_TAG_PREFIX = "ip:";
const DEFAULT_CAP_BYTES = 900_000_000_000;

/** Per-socket state; survives hibernation through serializeAttachment. */
interface SocketState {
  uuid: string;
  name: string;
  ip: string;
  bucket: Bucket;
  badMessages: number;
  limitedCount: number;
  limitedWindowStart: number;
}

type ServerMessage = Record<string, unknown> & { t: string };

/**
 * The single signaling hub. Sockets are tagged with the player UUID (routing) and the client IP
 * (connection cap); host state, allow-lists, pending joins, issued TURN credentials and the
 * monthly relay cap live in SQLite so they survive hibernation and restarts.
 */
export class Hub extends DurableObject<Env> {
  private readonly sql: SqlStorage;
  private readonly challengeLimiter = new WindowLimiter(CHALLENGES_PER_IP, CHALLENGE_WINDOW_MS);

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    // "hosts" (with the dropped advertise flag) only held live sessions; open_hosts replaces it.
    this.sql.exec(`
      DROP TABLE IF EXISTS hosts;
      CREATE TABLE IF NOT EXISTS open_hosts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL,
        mode TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS allow (host TEXT NOT NULL, uuid TEXT NOT NULL,
        PRIMARY KEY (host, uuid)) WITHOUT ROWID;
      CREATE INDEX IF NOT EXISTS allow_by_uuid ON allow (uuid);
      CREATE TABLE IF NOT EXISTS pending (host TEXT NOT NULL, peer TEXT NOT NULL,
        conv INTEGER NOT NULL, expires INTEGER NOT NULL, PRIMARY KEY (host, peer)) WITHOUT ROWID;
      CREATE TABLE IF NOT EXISTS turn_creds (username TEXT PRIMARY KEY, expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    `);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  // ---- RPC from the Worker ----

  /** 0 when this IP may get a challenge, otherwise seconds to wait. */
  allowChallenge(ip: string): number {
    return this.challengeLimiter.hit(ip, Date.now());
  }

  /** Mojang player-certificate keys last fetched by the cron, or null. */
  playerCertificateKeys(): string[] | null {
    const stored = this.getMeta("player_certificate_keys");
    if (stored === null) return null;
    try {
      const keys = JSON.parse(stored) as unknown;
      return Array.isArray(keys) && keys.every((k) => typeof k === "string") ? keys : null;
    } catch {
      return null;
    }
  }

  storePlayerCertificateKeys(keys: string[]): void {
    this.setMeta("player_certificate_keys", JSON.stringify(keys));
  }

  /** Cron: stores month-to-date relay egress and enforces the monthly cap. */
  async applyUsage(egressBytes: number, month: string): Promise<boolean> {
    this.rollMonth(month);
    this.setMeta("usage_bytes", String(egressBytes));
    if (egressBytes >= this.capBytes() && this.getMeta("capped") !== "1") {
      this.setMeta("capped", "1");
      await this.revokeAllCredentials();
      this.broadcast({ t: "turn.revoked", reason: "monthly_limit" });
    }
    return this.getMeta("capped") === "1";
  }

  // ---- WebSocket lifecycle ----

  override async fetch(request: Request): Promise<Response> {
    const uuid = request.headers.get("X-OD-UUID");
    const name = request.headers.get("X-OD-Name");
    const ip = request.headers.get("X-OD-IP") ?? "unknown";
    if (request.headers.get("Upgrade") !== "websocket" || !isUuid(uuid) || !isName(name)) {
      return new Response("Bad request", { status: 400 });
    }
    const ipTag = IP_TAG_PREFIX + ip.slice(0, 64);
    const sameIp = this.ctx.getWebSockets(ipTag).filter((ws) => !this.hasUuid(ws, uuid));
    if (sameIp.length >= MAX_SOCKETS_PER_IP) {
      return new Response("Too many connections", { status: 429, headers: { "Retry-After": "60" } });
    }
    for (const old of this.ctx.getWebSockets(uuid)) {
      old.close(CLOSE_REPLACED, "replaced");
    }
    this.dropHostState(uuid);

    const pair = new WebSocketPair();
    const server = pair[1];
    this.ctx.acceptWebSocket(server, [uuid, ipTag]);
    const state: SocketState = {
      uuid,
      name,
      ip,
      bucket: fullBucket(Date.now()),
      badMessages: 0,
      limitedCount: 0,
      limitedWindowStart: 0,
    };
    server.serializeAttachment(state);
    server.send(JSON.stringify({ t: "welcome", uuid, name }));
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  override async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const state = ws.deserializeAttachment() as SocketState | null;
    if (state === null) {
      ws.close(CLOSE_POLICY_VIOLATION, "no session");
      return;
    }
    const now = Date.now();
    if (!take(state.bucket, now)) {
      if (now - state.limitedWindowStart >= RATE_LIMIT_WINDOW_MS) {
        state.limitedWindowStart = now;
        state.limitedCount = 0;
      }
      state.limitedCount++;
      ws.serializeAttachment(state);
      if (state.limitedCount >= RATE_LIMITED_BEFORE_CLOSE) {
        ws.close(CLOSE_POLICY_VIOLATION, "rate limited");
      } else {
        this.send(ws, { t: "error", code: "rate_limited", retryAfter: 1 });
      }
      return;
    }
    const parsed = typeof message === "string" ? parseClientMessage(message) : null;
    if (parsed === null) {
      state.badMessages++;
      ws.serializeAttachment(state);
      if (state.badMessages >= BAD_MESSAGES_BEFORE_CLOSE) {
        ws.close(CLOSE_POLICY_VIOLATION, "bad messages");
      } else {
        this.send(ws, { t: "error", code: "bad_message" });
      }
      return;
    }
    ws.serializeAttachment(state);
    await this.handle(ws, state, parsed, now);
  }

  override async webSocketClose(ws: WebSocket): Promise<void> {
    this.cleanUp(ws);
  }

  override async webSocketError(ws: WebSocket): Promise<void> {
    this.cleanUp(ws);
  }

  // ---- Message handling ----

  private async handle(
    ws: WebSocket,
    self: SocketState,
    message: ClientMessage,
    now: number,
  ): Promise<void> {
    switch (message.t) {
      case "host.open":
      case "host.update": {
        if (message.t === "host.update" && !this.isHosting(self.uuid)) {
          this.send(ws, { t: "error", code: "not_hosting" });
          return;
        }
        this.sql.exec(
          "INSERT OR REPLACE INTO open_hosts (uuid, name, mode) VALUES (?, ?, ?)",
          self.uuid,
          self.name,
          message.mode,
        );
        const before = new Set(this.allowedBy(self.uuid));
        const after = new Set(message.allow);
        after.delete(self.uuid);
        this.sql.exec("DELETE FROM allow WHERE host = ?", self.uuid);
        for (const allowed of after) {
          this.sql.exec("INSERT INTO allow (host, uuid) VALUES (?, ?)", self.uuid, allowed);
          if (!before.has(allowed)) this.pushDirectory(allowed, self.uuid, true);
        }
        for (const removed of before) {
          if (!after.has(removed)) this.pushDirectory(removed, self.uuid, false);
        }
        return;
      }
      case "host.close":
        this.dropHostState(self.uuid);
        return;
      case "directory.query": {
        const wanted = new Set(message.uuids);
        const joinable: string[] = [];
        const rows = this.sql.exec<{ uuid: string }>(
          "SELECT h.uuid AS uuid FROM open_hosts h JOIN allow a ON a.host = h.uuid WHERE a.uuid = ?",
          self.uuid,
        );
        for (const row of rows) {
          if (wanted.has(row.uuid) && this.isOnline(row.uuid)) joinable.push(row.uuid);
        }
        this.send(ws, { t: "directory.result", joinable });
        return;
      }
      case "invite.send": {
        if (!this.isHosting(self.uuid)) {
          this.send(ws, { t: "error", code: "not_hosting" });
          return;
        }
        if (message.to === self.uuid) return;
        this.sql.exec("INSERT OR IGNORE INTO allow (host, uuid) VALUES (?, ?)", self.uuid, message.to);
        this.sendTo(message.to, { t: "invite.recv", from: self.uuid, name: self.name });
        return;
      }
      case "join.request": {
        if (message.host === self.uuid || !this.isOnline(message.host) || !this.allows(message.host, self.uuid)) {
          this.send(ws, { t: "join.reject", host: message.host, reason: "closed" });
          return;
        }
        const conv = randomConv();
        this.sql.exec(
          "INSERT OR REPLACE INTO pending (host, peer, conv, expires) VALUES (?, ?, ?, ?)",
          message.host,
          self.uuid,
          conv,
          now + PENDING_JOIN_MS,
        );
        this.sendTo(message.host, {
          t: "join.offer",
          peer: self.uuid,
          name: self.name,
          conv,
          ufrag: message.ufrag,
          pwd: message.pwd,
          candidates: message.candidates,
        });
        return;
      }
      case "join.answer": {
        if (!this.takePending(self.uuid, message.peer, message.conv, now)) {
          this.send(ws, { t: "error", code: "not_allowed" });
          return;
        }
        this.sendTo(message.peer, {
          t: "join.answer",
          host: self.uuid,
          conv: message.conv,
          ufrag: message.ufrag,
          pwd: message.pwd,
          candidates: message.candidates,
        });
        return;
      }
      case "join.reject": {
        if (!this.takePending(self.uuid, message.peer, null, now)) {
          this.send(ws, { t: "error", code: "not_allowed" });
          return;
        }
        this.sendTo(message.peer, { t: "join.reject", host: self.uuid, reason: message.reason });
        return;
      }
      case "turn.request":
        await this.handleTurnRequest(ws, now);
        return;
    }
  }

  private async handleTurnRequest(ws: WebSocket, now: number): Promise<void> {
    this.rollMonth(monthKey(new Date(now)));
    if (this.getMeta("capped") === "1") {
      this.send(ws, { t: "turn.denied", reason: "monthly_limit" });
      return;
    }
    const key = this.turnKey();
    const credentials = key === null ? null : await mintTurnCredentials(key);
    if (credentials === null) {
      this.send(ws, { t: "turn.denied", reason: "unavailable" });
      return;
    }
    this.sql.exec("DELETE FROM turn_creds WHERE expires < ?", now);
    this.sql.exec(
      "INSERT OR REPLACE INTO turn_creds (username, expires) VALUES (?, ?)",
      credentials.username,
      now + TURN_TTL_SECONDS * 1000,
    );
    this.send(ws, { t: "turn.creds", ...credentials, ttl: TURN_TTL_SECONDS });
  }

  // ---- Helpers ----

  private takePending(host: string, peer: string, conv: number | null, now: number): boolean {
    const row = this.sql
      .exec<{ conv: number; expires: number }>(
        "SELECT conv, expires FROM pending WHERE host = ? AND peer = ?",
        host,
        peer,
      )
      .toArray()[0];
    if (row === undefined || row.expires < now || (conv !== null && row.conv !== conv)) {
      return false;
    }
    this.sql.exec("DELETE FROM pending WHERE host = ? AND peer = ?", host, peer);
    return true;
  }

  private isHosting(uuid: string): boolean {
    return this.sql.exec("SELECT 1 FROM open_hosts WHERE uuid = ?", uuid).toArray().length > 0;
  }

  private allows(host: string, uuid: string): boolean {
    return (
      this.sql.exec("SELECT 1 FROM allow WHERE host = ? AND uuid = ?", host, uuid).toArray()
        .length > 0
    );
  }

  private isOnline(uuid: string): boolean {
    return this.openSockets(uuid).length > 0;
  }

  private openSockets(uuid: string): WebSocket[] {
    return this.ctx.getWebSockets(uuid).filter((ws) => ws.readyState === WebSocket.OPEN);
  }

  private hasUuid(ws: WebSocket, uuid: string): boolean {
    return this.ctx.getTags(ws).includes(uuid);
  }

  /** Players the host allows; only they ever learn whether its world is open. */
  private allowedBy(host: string): string[] {
    return this.sql
      .exec<{ uuid: string }>("SELECT uuid FROM allow WHERE host = ?", host)
      .toArray()
      .map((row) => row.uuid);
  }

  /** Tells an online player that a host's world just became joinable for them, or stopped. */
  private pushDirectory(player: string, host: string, joinable: boolean): void {
    this.sendTo(player, { t: "directory.update", host, joinable });
  }

  private dropHostState(uuid: string): void {
    for (const allowed of this.allowedBy(uuid)) this.pushDirectory(allowed, uuid, false);
    this.sql.exec("DELETE FROM open_hosts WHERE uuid = ?", uuid);
    this.sql.exec("DELETE FROM allow WHERE host = ?", uuid);
    this.sql.exec("DELETE FROM pending WHERE host = ?", uuid);
  }

  private cleanUp(ws: WebSocket): void {
    const state = ws.deserializeAttachment() as SocketState | null;
    if (state === null) return;
    // A replaced socket closes after its successor opened: keep the successor's state.
    const others = this.openSockets(state.uuid).filter((other) => other !== ws);
    if (others.length === 0) this.dropHostState(state.uuid);
    this.sql.exec("DELETE FROM pending WHERE peer = ?", state.uuid);
  }

  private send(ws: WebSocket, message: ServerMessage): void {
    try {
      ws.send(JSON.stringify(message));
    } catch {
      // The socket is closing; its close handler cleans up.
    }
  }

  private sendTo(uuid: string, message: ServerMessage): void {
    for (const ws of this.openSockets(uuid)) this.send(ws, message);
  }

  private broadcast(message: ServerMessage): void {
    for (const ws of this.ctx.getWebSockets()) {
      if (ws.readyState === WebSocket.OPEN) this.send(ws, message);
    }
  }

  private rollMonth(month: string): void {
    if (this.getMeta("month") !== month) {
      this.setMeta("month", month);
      this.setMeta("capped", "0");
      this.setMeta("usage_bytes", "0");
    }
  }

  private async revokeAllCredentials(): Promise<void> {
    const key = this.turnKey();
    const now = Date.now();
    const rows = this.sql
      .exec<{ username: string }>("SELECT username FROM turn_creds WHERE expires >= ?", now)
      .toArray();
    if (key !== null) {
      await Promise.all(rows.map((row) => revokeTurnCredential(key, row.username)));
    }
    this.sql.exec("DELETE FROM turn_creds");
  }

  private turnKey(): TurnKey | null {
    const keyId = this.env.TURN_KEY_ID;
    const apiToken = this.env.TURN_KEY_API_TOKEN;
    return keyId && apiToken ? { keyId, apiToken } : null;
  }

  private capBytes(): number {
    const cap = Number(this.env.TURN_MONTHLY_CAP_BYTES);
    return Number.isFinite(cap) && cap > 0 ? cap : DEFAULT_CAP_BYTES;
  }

  private getMeta(key: string): string | null {
    const row = this.sql
      .exec<{ value: string }>("SELECT value FROM meta WHERE key = ?", key)
      .toArray()[0];
    return row === undefined ? null : row.value;
  }

  private setMeta(key: string, value: string): void {
    this.sql.exec("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", key, value);
  }
}

/**
 * Random conversation id. KCP writes conv little-endian, so its low byte is the first byte of
 * every data datagram; keeping it in 0x80-0xBF stays clear of STUN (0-3) and TURN ChannelData
 * (64-79) in the RFC 7983 first-byte demultiplexing.
 */
function randomConv(): number {
  const value = new Uint32Array(1);
  crypto.getRandomValues(value);
  const random = value[0] ?? 0;
  return ((random & 0xffffff00) | 0x80 | (random & 0x3f)) >>> 0;
}
