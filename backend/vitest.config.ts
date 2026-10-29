import { cloudflareTest } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        bindings: {
          DEV_AUTH: "0",
          AUTH_SECRET: "test-secret-0123456789abcdef0123456789abcdef",
          TURN_KEY_ID: "test-key",
          TURN_KEY_API_TOKEN: "test-token",
          ANALYTICS_API_TOKEN: "test-analytics",
          ACCOUNT_ID: "test-account",
          TURN_MONTHLY_CAP_BYTES: "900000000000",
        },
        // Tests never reach the real Mojang or Cloudflare APIs: every outbound request not
        // answered by an injected fetch ends here.
        outboundService: () => new Response("outbound blocked in tests", { status: 599 }),
      },
    }),
  ],
});
