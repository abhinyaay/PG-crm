import type { TokenResponse, UserInfo } from '@/api/types';

export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

export function memoryStorage(initial: Record<string, string> = {}) {
  const map = new Map(Object.entries(initial));
  return {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
    removeItem: (k: string) => void map.delete(k),
    map,
  };
}

export const demoUser: UserInfo = { id: 2, email: 'owner@sunrise.example.com', fullName: 'Sunrise Owner', role: 'PROPERTY_ADMIN', organizationId: 1 };

export function tokens(n: number, user: UserInfo = demoUser, ttlMs = 15 * 60_000): TokenResponse {
  return {
    tokenType: 'Bearer',
    accessToken: `access-${n}`,
    accessTokenExpiresAt: new Date(Date.now() + ttlMs).toISOString(),
    refreshToken: `refresh-${n}`,
    refreshTokenExpiresAt: new Date(Date.now() + 14 * 86_400_000).toISOString(),
    user,
  };
}

/** A fake same-origin BroadcastChannel bus connecting several SessionManagers (simulated tabs). */
export function channelBus() {
  const members = new Set<{ listeners: ((e: MessageEvent) => void)[] }>();
  return () => {
    const self = { listeners: [] as ((e: MessageEvent) => void)[] };
    members.add(self);
    return {
      postMessage(message: unknown) {
        for (const m of members) if (m !== self) for (const l of m.listeners) setTimeout(() => l({ data: message } as MessageEvent), 0);
      },
      addEventListener(_t: 'message', l: (e: MessageEvent) => void) {
        self.listeners.push(l);
      },
    };
  };
}

/** Serialising lock, like navigator.locks (one holder at a time, FIFO). */
export function fakeLocks() {
  let tail: Promise<unknown> = Promise.resolve();
  return {
    request<T>(_name: string, cb: () => Promise<T>): Promise<T> {
      const run = tail.then(cb, cb);
      tail = run.catch(() => undefined);
      return run;
    },
  };
}
