// Dev check against a local `npx wrangler dev --var DEV_AUTH:1` (never production):
// two offline identities host, find, invite and exchange a join offer/answer.
// Usage: node scripts/dev-invite-check.mjs [http://127.0.0.1:8787]
const base = (process.argv[2] ?? "http://127.0.0.1:8787").replace(/^http/, "ws");
const TIMEOUT_MS = 5000;
let failed = false;

function report(ok, label) {
  console.log(`${ok ? "PASS" : "FAIL"} ${label}`);
  if (!ok) failed = true;
}

function connect(name) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${base}/v1/ws?dev=1&name=${name}`);
    const queue = [];
    const waiters = [];
    ws.addEventListener("message", (event) => {
      if (event.data === "pong") return;
      queue.push(JSON.parse(event.data));
      for (const wake of waiters.splice(0)) wake();
    });
    ws.addEventListener("error", () => reject(new Error(`cannot connect as ${name}`)));
    const client = {
      ws,
      send: (message) => ws.send(JSON.stringify(message)),
      async next(type) {
        const deadline = Date.now() + TIMEOUT_MS;
        for (;;) {
          const index = queue.findIndex((m) => m.t === type);
          if (index >= 0) return queue.splice(index, 1)[0];
          if (Date.now() > deadline) throw new Error(`${name}: timed out waiting for ${type}`);
          await new Promise((wake) => {
            waiters.push(wake);
            setTimeout(wake, 100);
          });
        }
      },
    };
    ws.addEventListener("open", async () => {
      try {
        const welcome = await client.next("welcome");
        client.uuid = welcome.uuid;
        resolve(client);
      } catch (e) {
        reject(e);
      }
    });
  });
}

const ice = (ufrag) => ({
  ufrag,
  pwd: "abcdefghijklmnopqrstuvwxyz012345",
  candidates: [{ k: "host", a: "127.0.0.1", p: 40000 }],
});

try {
  const alice = await connect("Alice");
  const bob = await connect("Bob");
  report(alice.uuid === "10920508-d5d8-3eed-93d2-92f193afe7d7", `Alice welcome ${alice.uuid}`);
  report(bob.uuid === "faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b", `Bob welcome ${bob.uuid}`);

  alice.send({ t: "host.open", mode: "friends", allow: [bob.uuid] });
  alice.send({ t: "directory.query", uuids: [] });
  await alice.next("directory.result");

  bob.send({ t: "directory.query", uuids: [alice.uuid] });
  const directory = await bob.next("directory.result");
  report(directory.joinable.includes(alice.uuid), "Bob sees Alice in the directory");

  alice.send({ t: "invite.send", to: bob.uuid });
  const invite = await bob.next("invite.recv");
  report(invite.from === alice.uuid && invite.name === "Alice", "Bob receives Alice's invite");

  bob.send({ t: "join.request", host: alice.uuid, ...ice("bobb") });
  const offer = await alice.next("join.offer");
  report(offer.peer === bob.uuid && offer.ufrag === "bobb" && offer.conv > 0, `Alice gets join.offer conv=${offer.conv}`);

  alice.send({ t: "join.answer", peer: bob.uuid, conv: offer.conv, ...ice("alic") });
  const answer = await bob.next("join.answer");
  report(answer.host === alice.uuid && answer.conv === offer.conv && answer.ufrag === "alic", "Bob gets join.answer with the same conv");

  alice.ws.send("ping");
  alice.ws.close();
  bob.ws.close();
} catch (e) {
  report(false, e.message);
}
process.exit(failed ? 1 : 0);
