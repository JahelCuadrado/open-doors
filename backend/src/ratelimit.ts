/** Token bucket state, small enough to live in a WebSocket attachment. */
export interface Bucket {
  tokens: number;
  last: number;
}

export const MESSAGE_BUCKET_CAPACITY = 20;
export const MESSAGE_REFILL_PER_SECOND = 5;

export function fullBucket(now: number): Bucket {
  return { tokens: MESSAGE_BUCKET_CAPACITY, last: now };
}

/** Takes one token; returns false (bucket unchanged apart from refill) when empty. */
export function take(bucket: Bucket, now: number): boolean {
  const elapsed = Math.max(0, now - bucket.last) / 1000;
  bucket.tokens = Math.min(
    MESSAGE_BUCKET_CAPACITY,
    bucket.tokens + elapsed * MESSAGE_REFILL_PER_SECOND,
  );
  bucket.last = now;
  if (bucket.tokens < 1) return false;
  bucket.tokens -= 1;
  return true;
}

/** Fixed-window counter per key (challenge requests per IP). */
export class WindowLimiter {
  private readonly windows = new Map<string, { start: number; count: number }>();

  constructor(
    private readonly limit: number,
    private readonly windowMs: number,
  ) {}

  /** 0 when allowed, otherwise seconds until the window resets. */
  hit(key: string, now: number): number {
    let window = this.windows.get(key);
    if (window === undefined || now - window.start >= this.windowMs) {
      if (this.windows.size > 10_000) this.prune(now);
      window = { start: now, count: 0 };
      this.windows.set(key, window);
    }
    if (window.count >= this.limit) {
      return Math.max(1, Math.ceil((window.start + this.windowMs - now) / 1000));
    }
    window.count++;
    return 0;
  }

  private prune(now: number): void {
    for (const [key, window] of this.windows) {
      if (now - window.start >= this.windowMs) this.windows.delete(key);
    }
  }
}
