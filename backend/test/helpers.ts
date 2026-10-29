import { env } from "cloudflare:test";
import worker from "../src/index";
import type { Env } from "../src/env";

export type Message = Record<string, unknown> & { t: string };

let ipCounter = 0;

/** Unique client IP per call, so per-IP limits never couple tests. */
export function freshIp(): string {
  ipCounter++;
  return `198.51.${(ipCounter >> 8) & 255}.${ipCounter & 255}`;
}

let nameCounter = 0;

/** Unique offline player name per call (the hub is one global object shared by tests). */
export function freshName(prefix: string): string {
  nameCounter++;
  return `${prefix}${Date.now() % 100000}${nameCounter}`.slice(0, 16);
}

export function devEnv(): Env {
  return { ...env, DEV_AUTH: "1" };
}

/** Test-side WebSocket client with an awaitable message queue. */
export class TestClient {
  readonly received: Message[] = [];
  readonly raw: string[] = [];
  closeCode: number | null = null;
  private waiters: (() => void)[] = [];

  constructor(readonly ws: WebSocket) {
    ws.accept();
    ws.addEventListener("message", (event) => {
      const data = String(event.data);
      this.raw.push(data);
      if (data !== "pong") this.received.push(JSON.parse(data) as Message);
      this.wake();
    });
    ws.addEventListener("close", (event) => {
      this.closeCode = event.code;
      this.wake();
    });
  }

  private wake(): void {
    const waiters = this.waiters;
    this.waiters = [];
    for (const waiter of waiters) waiter();
  }

  send(message: unknown): void {
    this.ws.send(typeof message === "string" ? message : JSON.stringify(message));
  }

  /** Waits for (and removes) the first message of the given type. */
  async next(type: string, timeoutMs = 2000): Promise<Message> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const index = this.received.findIndex((m) => m.t === type);
      if (index >= 0) return this.received.splice(index, 1)[0] as Message;
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${type}`);
      await this.waitAny(deadline - Date.now());
    }
  }

  async waitClosed(timeoutMs = 2000): Promise<number> {
    const deadline = Date.now() + timeoutMs;
    while (this.closeCode === null) {
      if (Date.now() > deadline) throw new Error("timed out waiting for close");
      await this.waitAny(deadline - Date.now());
    }
    return this.closeCode;
  }

  async waitRaw(value: string, timeoutMs = 2000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (!this.raw.includes(value)) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${value}`);
      await this.waitAny(deadline - Date.now());
    }
  }

  /** Lets in-flight messages arrive, then asserts none of the type came. */
  async expectNone(type: string, settleMs = 150): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, settleMs));
    if (this.received.some((m) => m.t === type)) throw new Error(`unexpected ${type}`);
  }

  close(): void {
    try {
      this.ws.close(1000, "done");
    } catch {
      // Already closed.
    }
  }

  private waitAny(timeoutMs: number): Promise<void> {
    return new Promise((resolve) => {
      const timer = setTimeout(resolve, Math.max(1, timeoutMs));
      this.waiters.push(() => {
        clearTimeout(timer);
        resolve();
      });
    });
  }
}

/** Opens a socket for an offline (DEV_AUTH) identity and consumes its welcome. */
export async function connectDev(name: string): Promise<{ client: TestClient; uuid: string }> {
  const response = await worker.fetch(
    new Request(`http://hub.test/v1/ws?dev=1&name=${name}`, {
      headers: { Upgrade: "websocket", "CF-Connecting-IP": freshIp() },
    }),
    devEnv(),
  );
  if (response.status !== 101 || response.webSocket === null) {
    throw new Error(`connect failed: ${response.status}`);
  }
  const client = new TestClient(response.webSocket);
  const welcome = await client.next("welcome");
  return { client, uuid: String(welcome.uuid) };
}

export const ICE = {
  ufrag: "abcd",
  pwd: "abcdefghijklmnopqrstuvwx",
  candidates: [
    { k: "host", a: "192.168.1.20", p: 50000 },
    { k: "srflx", a: "2001:db8::1", p: 50001 },
  ],
};

/**
 * Round trip on one socket: the hub handles each socket's messages in order, but messages of
 * different sockets race, so a test syncs the sender before another socket depends on its effect.
 */
export async function sync(client: TestClient): Promise<void> {
  client.send({ t: "directory.query", uuids: [] });
  await client.next("directory.result");
}
