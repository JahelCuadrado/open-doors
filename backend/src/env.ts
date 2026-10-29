import type { Hub } from "./hub";

/** Bindings, vars and secrets of the Worker (see wrangler.jsonc). */
export interface Env {
  HUB: DurableObjectNamespace<Hub>;
  /** "1" only under `wrangler dev`: offline identities without Mojang authentication. */
  DEV_AUTH: string;
  AUTH_SECRET: string;
  TURN_KEY_ID?: string;
  TURN_KEY_API_TOKEN?: string;
  ANALYTICS_API_TOKEN?: string;
  ACCOUNT_ID?: string;
  TURN_MONTHLY_CAP_BYTES: string;
}
