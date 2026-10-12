import { ApiClient } from './client';
import { SessionManager, REFRESH_TOKEN_KEY, CHANNEL_NAME, type ChannelLike, type LockLike } from './session';

function browserLocks(): LockLike | undefined {
  if (typeof navigator !== 'undefined' && 'locks' in navigator && navigator.locks) {
    const locks = navigator.locks;
    return { request: <T,>(name: string, cb: () => Promise<T>) => locks.request(name, () => cb()) as Promise<T> };
  }
  return undefined;
}

function browserChannel(): ChannelLike | undefined {
  if (typeof BroadcastChannel === 'undefined') return undefined;
  return new BroadcastChannel(CHANNEL_NAME);
}

function browserStorage() {
  try {
    return window.localStorage;
  } catch {
    const mem = new Map<string, string>();
    return {
      getItem: (k: string) => mem.get(k) ?? null,
      setItem: (k: string, v: string) => void mem.set(k, v),
      removeItem: (k: string) => void mem.delete(k),
    };
  }
}

export const session = new SessionManager({
  fetch: (...args) => fetch(...args),
  storage: browserStorage(),
  locks: browserLocks(),
  channel: browserChannel(),
  isOnline: () => (typeof navigator === 'undefined' ? true : navigator.onLine !== false),
});

// Another tab signed out (refresh token removed) -> sign out here too.
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key === REFRESH_TOKEN_KEY && e.newValue === null) session.handleExternalSignOut();
  });
}

export const api = new ApiClient(session);

export { ApiError, isApiError, errorMessage, applyFieldErrors } from './errors';
export type * from './types';
