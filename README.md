# Open Doors

Play your singleplayer world with your friends over the internet, like a LAN world but for
anyone on your Friends List. No server to rent, no port forwarding, no IP addresses.

Fabric mod for Minecraft 26.3. The host and the friends who join all need it.

## Features
- **Open your world to friends:** in the Friends List (Friends button or `O`), set "My world" to
  Closed, Open to friends or Invited only. LAN keeps working as before, separately.
- **See whose world is open:** a green dot on the Friends button tells you a friend's world is
  open to you; they show first in your Friends List, with a Join button.
- **Invite a friend:** press Invite next to any friend, even with your world closed (it opens to
  invited players). They get a toast and join from their Friends List, or press `K` in game.
- **Your world, your rules:** "Players…" in the Friends List shows who is playing; kick or block
  players. Invites and blocks are saved with each world.
- **Secure:** every player logs in with their real Minecraft account and the connection is
  encrypted, exactly like a normal server.
- **Fast:** players connect directly to each other, with no server in between. The mod opens
  the port on your router by itself when the router allows it (UPnP, NAT-PMP or PCP). There is no
  relay yet: some networks (mobile data, company or campus networks, VPNs) may not connect.
- **Lightweight:** no work at all while nobody is connected.

## Requirements

Minecraft 26.3 · Fabric Loader 0.19.5+ · Fabric API · Java 25. Mod Menu optional.

## Development

Requires JDK 25. `./gradlew runClient` · `./gradlew build` (compile, format check, JUnit)
· `./gradlew runClientGameTest` (in-game tests, opens a small muted window) · `./gradlew
spotlessApply`. The signaling backend lives in `backend/` (Cloudflare Worker, `npm test`; deploy
steps in `backend/DEPLOY.md`). Dev runs (`runClient` as Alice, `runClientBob` as Bob) and the
two-client test (`runE2eHost` + `runE2eJoiner`) use a local `npx wrangler dev` started with
`DEV_AUTH=1`. `./gradlew runLatencyHarness` measures the connection on this machine.

### Testing a real connection without a friend

1. On every computer you will use, run `./gradlew runLatencyHarness -Pmode=nat` once per network:
   it prints the public address, the NAT type ("endpoint-independent" is good, "symmetric" means
   direct connections need the other side to be open) and whether the router accepted a port
   mapping (UPnP, NAT-PMP or PCP). Run it with any VPN on and off: a VPN usually makes the NAT
   symmetric.
2. Turn on **Connection diagnostics** in the mod settings on both games (or start a dev run with
   `-Dopendoors.diagnostics=true`). Every step goes to `logs/latest.log` under
   "Open Doors diagnostics".
3. Host on the home network (Wi-Fi or cable) and join from a second computer connected to a phone
   hotspot (mobile data), so the two games are on different networks, as with a real friend.
4. Compare both logs: `local candidates` / `remote candidates` (what each side offered), `first
   check sent` and `first check received` per address (whether packets got through in each
   direction), `NAT type`, `port mapping`, and the final `ended:` line with per-address counts.

## License

MIT. Includes a Java port of KCP (MIT, see `THIRD_PARTY_NOTICES`).
