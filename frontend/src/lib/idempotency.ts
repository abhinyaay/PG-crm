/** Deterministic JSON (sorted keys) so that logically equal request bodies compare equal. */
export function stableStringify(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value) ?? 'undefined';
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  const entries = Object.entries(value as Record<string, unknown>)
    .filter(([, v]) => v !== undefined)
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
  return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${stableStringify(v)}`).join(',')}}`;
}

export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  // Fallback for very old browsers / insecure contexts (crypto.randomUUID needs a secure context).
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * Tracks the Idempotency-Key of ONE logical submission (e.g. one "Record payment" form).
 *
 * - The first submit generates a key.
 * - Retrying the same request body (after a timeout, network error, 5xx …) reuses the key, so the backend
 *   replays the original result instead of creating a duplicate payment.
 * - Changing the body means a different submission → a new key (the backend rejects a reused key with a different
 *   body with 422 IDEMPOTENCY_KEY_REUSED).
 * - After a confirmed success call `reset()` so the next payment gets a fresh key.
 */
export class IdempotentSubmission<T> {
  private key: string | null = null;
  private fingerprint: string | null = null;

  constructor(private readonly generate: () => string = newIdempotencyKey) {}

  keyFor(body: T): string {
    const fp = stableStringify(body);
    if (this.key !== null && fp === this.fingerprint) return this.key;
    this.key = this.generate();
    this.fingerprint = fp;
    return this.key;
  }

  /** The key of the pending submission, if any. */
  get currentKey(): string | null {
    return this.key;
  }

  reset(): void {
    this.key = null;
    this.fingerprint = null;
  }
}
