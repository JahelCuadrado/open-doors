import { createScheduledController, createExecutionContext, env, waitOnExecutionContext } from "cloudflare:test";
import { afterEach, describe, expect, it } from "vitest";
import worker from "../src/index";
import { setOutboundFetch } from "../src/outbound";
import { GRAPHQL_URL, monthKey } from "../src/usage";
import { ICE, connectDev, freshName, sync } from "./helpers";

function hubStub() {
  return env.HUB.get(env.HUB.idFromName("hub"));
}

afterEach(() => setOutboundFetch(null));

describe("hosting and directory", () => {
  it("lists a host only to players in its allow-list", async () => {
    const host = await connectDev(freshName("Host"));
    const friend = await connectDev(freshName("Friend"));
    const stranger = await connectDev(freshName("Strgr"));
    host.client.send({ t: "host.open", mode: "friends", allow: [friend.uuid] });
    await sync(host.client);

    friend.client.send({ t: "directory.query", uuids: [host.uuid, stranger.uuid] });
    expect((await friend.client.next("directory.result")).joinable).toEqual([host.uuid]);
    stranger.client.send({ t: "directory.query", uuids: [host.uuid] });
    expect((await stranger.client.next("directory.result")).joinable).toEqual([]);

    // The allow-list alone decides (the client lists only who may join in its current mode).
    host.client.send({ t: "host.update", mode: "invited", allow: [friend.uuid] });
    await sync(host.client);
    friend.client.send({ t: "directory.query", uuids: [host.uuid] });
    expect((await friend.client.next("directory.result")).joinable).toEqual([host.uuid]);

    host.client.send({ t: "host.close" });
    await sync(host.client);
    friend.client.send({ t: "directory.query", uuids: [host.uuid] });
    expect((await friend.client.next("directory.result")).joinable).toEqual([]);

    host.client.send({ t: "host.update", mode: "friends", allow: [] });
    await sync(host.client);
    expect((await host.client.next("error")).code).toBe("not_hosting");
    for (const c of [host, friend, stranger]) c.client.close();
  });

  it("pushes directory updates to allowed players only, on open, changes and close", async () => {
    const host = await connectDev(freshName("Host"));
    const friend = await connectDev(freshName("Friend"));
    const other = await connectDev(freshName("Other"));
    const stranger = await connectDev(freshName("Strgr"));
    const update = (joinable: boolean) => ({ t: "directory.update", host: host.uuid, joinable });

    host.client.send({ t: "host.open", mode: "friends", allow: [friend.uuid] });
    expect(await friend.client.next("directory.update")).toEqual(update(true));

    // Only changes are pushed: the friend hears nothing new, the added player hears it opened.
    host.client.send({
      t: "host.update",
      mode: "friends",
      allow: [friend.uuid, other.uuid],
    });
    expect(await other.client.next("directory.update")).toEqual(update(true));
    await friend.client.expectNone("directory.update");

    // Leaving the allow-list (blocked, or no longer invited) reads as closed.
    host.client.send({ t: "host.update", mode: "invited", allow: [other.uuid] });
    expect(await friend.client.next("directory.update")).toEqual(update(false));

    host.client.send({ t: "host.close" });
    expect(await other.client.next("directory.update")).toEqual(update(false));
    await stranger.client.expectNone("directory.update");
    for (const c of [host, friend, other, stranger]) c.client.close();
  });

  it("pushes closed to allowed players when the host disconnects", async () => {
    const host = await connectDev(freshName("Host"));
    const friend = await connectDev(freshName("Friend"));
    host.client.send({ t: "host.open", mode: "friends", allow: [friend.uuid] });
    expect((await friend.client.next("directory.update")).joinable).toBe(true);
    host.client.close();
    expect(await friend.client.next("directory.update")).toEqual({
      t: "directory.update",
      host: host.uuid,
      joinable: false,
    });
    friend.client.close();
  });

  it("forgets a host whose socket closed", async () => {
    const host = await connectDev(freshName("Host"));
    const friend = await connectDev(freshName("Friend"));
    host.client.send({ t: "host.open", mode: "friends", allow: [friend.uuid] });
    await sync(host.client);
    friend.client.send({ t: "directory.query", uuids: [host.uuid] });
    expect((await friend.client.next("directory.result")).joinable).toEqual([host.uuid]);
    host.client.close();
    await host.client.waitClosed().catch(() => 0);
    await new Promise((resolve) => setTimeout(resolve, 100));
    friend.client.send({ t: "directory.query", uuids: [host.uuid] });
    expect((await friend.client.next("directory.result")).joinable).toEqual([]);
    friend.client.close();
  });

  it("replaces an older socket of the same player", async () => {
    const name = freshName("Twice");
    const first = await connectDev(name);
    const second = await connectDev(name);
    expect(await first.client.waitClosed()).toBe(4000);
    second.client.send("ping");
    await second.client.waitRaw("pong");
    second.client.close();
  });
});

describe("invites and joins", () => {
  it("delivers invites and lets the invited player join", async () => {
    const hostName = freshName("Host");
    const host = await connectDev(hostName);
    const guest = await connectDev(freshName("Guest"));
    guest.client.send({ t: "invite.send", to: host.uuid });
    expect((await guest.client.next("error")).code).toBe("not_hosting");

    host.client.send({ t: "host.open", mode: "invited", allow: [] });
    await sync(host.client);
    host.client.send({ t: "invite.send", to: guest.uuid });
    await sync(host.client);
    expect(await guest.client.next("invite.recv")).toEqual({
      t: "invite.recv",
      from: host.uuid,
      name: hostName,
    });

    guest.client.send({ t: "join.request", host: host.uuid, ...ICE });
    const offer = await host.client.next("join.offer");
    expect(offer.peer).toBe(guest.uuid);
    expect(offer.ufrag).toBe(ICE.ufrag);
    expect(offer.candidates).toEqual(ICE.candidates);
    const conv = offer.conv as number;
    expect(conv).toBeGreaterThanOrEqual(1);
    expect(conv).toBeLessThanOrEqual(0xffffffff);
    expect(conv & 0xc0).toBe(0x80);

    const answerIce = { ufrag: "wxyz", pwd: "ZYXWVUTSRQPONMLKJIHGFEDCBA", candidates: [] };
    host.client.send({ t: "join.answer", peer: guest.uuid, conv: conv + 1 > 0xffffffff ? 1 : conv + 1, ...answerIce });
    expect((await host.client.next("error")).code).toBe("not_allowed");
    host.client.send({ t: "join.answer", peer: guest.uuid, conv, ...answerIce });
    expect(await guest.client.next("join.answer")).toEqual({
      t: "join.answer",
      host: host.uuid,
      conv,
      ...answerIce,
    });
    // The pending join is consumed: a second answer is refused.
    host.client.send({ t: "join.answer", peer: guest.uuid, conv, ...answerIce });
    expect((await host.client.next("error")).code).toBe("not_allowed");
    host.client.close();
    guest.client.close();
  });

  it("relays a host's rejection", async () => {
    const host = await connectDev(freshName("Host"));
    const guest = await connectDev(freshName("Guest"));
    host.client.send({ t: "host.open", mode: "invited", allow: [guest.uuid] });
    await sync(host.client);
    guest.client.send({ t: "join.request", host: host.uuid, ...ICE });
    await host.client.next("join.offer");
    host.client.send({ t: "join.reject", peer: guest.uuid, reason: "full" });
    expect(await guest.client.next("join.reject")).toEqual({
      t: "join.reject",
      host: host.uuid,
      reason: "full",
    });
    host.client.close();
    guest.client.close();
  });

  it("answers closed, without contacting the host, when the joiner is not allowed", async () => {
    const host = await connectDev(freshName("Host"));
    const stranger = await connectDev(freshName("Strgr"));
    host.client.send({ t: "host.open", mode: "friends", allow: [] });
    await sync(host.client);
    stranger.client.send({ t: "join.request", host: host.uuid, ...ICE });
    expect(await stranger.client.next("join.reject")).toEqual({
      t: "join.reject",
      host: host.uuid,
      reason: "closed",
    });
    await host.client.expectNone("join.offer");
    // Same answer for a host that does not exist (no existence leak).
    const nobody = "00000000-0000-4000-8000-000000000000";
    stranger.client.send({ t: "join.request", host: nobody, ...ICE });
    expect((await stranger.client.next("join.reject")).reason).toBe("closed");
    host.client.close();
    stranger.client.close();
  });
});

describe("validation and rate limits", () => {
  it("rejects malformed messages and closes with 1008 after five", async () => {
    const player = await connectDev(freshName("Bad"));
    const bad = [
      "not json",
      { t: "host.open", mode: "friends", allow: [], extra: 1 },
      { t: "join.request", host: "not-a-uuid", ...ICE },
      { t: "join.request", host: "00000000-0000-4000-8000-000000000000", ...ICE, candidates: [{ k: "host", a: "evil.example", p: 1 }] },
      { t: "nope" },
    ];
    for (let i = 0; i < 4; i++) {
      player.client.send(bad[i]);
      expect((await player.client.next("error")).code).toBe("bad_message");
    }
    player.client.send(bad[4]);
    expect(await player.client.waitClosed()).toBe(1008);
  });

  it("rate limits a flooding player, then closes with 1008", async () => {
    const player = await connectDev(freshName("Flood"));
    for (let i = 0; i < 40; i++) player.client.send({ t: "directory.query", uuids: [] });
    expect((await player.client.next("error")).code).toBe("rate_limited");
    expect(await player.client.waitClosed()).toBe(1008);
  });

  it("answers ping with pong", async () => {
    const player = await connectDev(freshName("Ping"));
    player.client.send("ping");
    await player.client.waitRaw("pong");
    player.client.close();
  });
});

describe("TURN relay and monthly cap", () => {
  it("mints UDP credentials, then denies and revokes them once the cap is reached", async () => {
    const issued: string[] = [];
    const revoked: string[] = [];
    let graphqlBytes = 0;
    setOutboundFetch(async (url, init) => {
      if (url.endsWith("/credentials/generate-ice-servers")) {
        expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer test-token");
        const username = `user${issued.length}`;
        issued.push(username);
        return Response.json(
          {
            iceServers: [
              { urls: "stun:stun.cloudflare.com:3478" },
              {
                urls: [
                  "turn:turn.cloudflare.com:3478?transport=udp",
                  "turn:turn.cloudflare.com:3478?transport=tcp",
                  "turns:turn.cloudflare.com:5349?transport=tcp",
                  "turn:turn.cloudflare.com:53?transport=udp",
                ],
                username,
                credential: "secret",
              },
            ],
          },
          { status: 201 },
        );
      }
      const revoke = /\/credentials\/([^/]+)\/revoke$/.exec(url);
      if (revoke !== null) {
        revoked.push(revoke[1] ?? "");
        return new Response(null, { status: 204 });
      }
      if (url === GRAPHQL_URL) {
        const body = JSON.parse(String(init?.body)) as { variables: Record<string, string> };
        expect(body.variables.accountTag).toBe("test-account");
        expect(body.variables.keyId).toBe("test-key");
        expect(body.variables.from).toMatch(/^\d{4}-\d{2}-01$/);
        return Response.json({
          data: {
            viewer: {
              accounts: [{ callsTurnUsageAdaptiveGroups: [{ sum: { egressBytes: graphqlBytes } }] }],
            },
          },
          errors: null,
        });
      }
      return new Response("unexpected", { status: 500 });
    });

    const player = await connectDev(freshName("Relay"));
    const other = await connectDev(freshName("Other"));
    player.client.send({ t: "turn.request" });
    expect(await player.client.next("turn.creds")).toEqual({
      t: "turn.creds",
      urls: [
        "turn:turn.cloudflare.com:3478?transport=udp",
        "turn:turn.cloudflare.com:53?transport=udp",
      ],
      username: "user0",
      credential: "secret",
      ttl: 3600,
    });

    // Cron under the cap: nothing happens.
    const scheduledTime = Date.now();
    graphqlBytes = 899_000_000_000;
    let ctx = createExecutionContext();
    await worker.scheduled(createScheduledController({ scheduledTime, cron: "*/15 * * * *" }), env, ctx);
    await waitOnExecutionContext(ctx);
    expect(revoked).toEqual([]);

    // Cron at the cap: revoke every active credential and tell everybody.
    graphqlBytes = 900_000_000_000;
    ctx = createExecutionContext();
    await worker.scheduled(createScheduledController({ scheduledTime, cron: "*/15 * * * *" }), env, ctx);
    await waitOnExecutionContext(ctx);
    expect(revoked).toContain("user0");
    expect(await player.client.next("turn.revoked")).toEqual({ t: "turn.revoked", reason: "monthly_limit" });
    expect((await other.client.next("turn.revoked")).reason).toBe("monthly_limit");

    player.client.send({ t: "turn.request" });
    expect(await player.client.next("turn.denied")).toEqual({ t: "turn.denied", reason: "monthly_limit" });
    expect(issued).toEqual(["user0"]);
    player.client.close();
    other.client.close();
  });

  it("lifts the cap when a new month starts", async () => {
    setOutboundFetch(async (url) =>
      url.endsWith("/generate-ice-servers")
        ? Response.json({
            iceServers: [
              { urls: ["turn:turn.cloudflare.com:3478?transport=udp"], username: "fresh", credential: "c" },
            ],
          }, { status: 201 })
        : new Response(null, { status: 204 }),
    );
    // Capped during a past month...
    expect(await hubStub().applyUsage(1e15, "2000-01")).toBe(true);
    // ...a request in the current month starts a new budget.
    const player = await connectDev(freshName("Month"));
    player.client.send({ t: "turn.request" });
    expect((await player.client.next("turn.creds")).username).toBe("fresh");
    // A usage report for the current month below the cap keeps it open.
    expect(await hubStub().applyUsage(1, monthKey(new Date()))).toBe(false);
    player.client.close();
  });

  it("denies relay when Cloudflare refuses to mint credentials", async () => {
    setOutboundFetch(async () => new Response("no", { status: 401 }));
    const player = await connectDev(freshName("NoKey"));
    player.client.send({ t: "turn.request" });
    expect(await player.client.next("turn.denied")).toEqual({ t: "turn.denied", reason: "unavailable" });
    player.client.close();
  });
});
