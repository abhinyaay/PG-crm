import type { TokenResponse, UserInfo } from './types';
import { ApiError, parseErrorResponse } from './errors';

/**
 * Session / token management.
 *
 * - The ACCESS token (15 min JWT) lives only in memory (this object). It is never written to storage.
 * - The REFRESH token (opaque, 14 days, rotated on every use) is persisted in localStorage so a reload or a new tab
 *   keeps the user signed in. See README "Auth & token storage" for the trade-off.
 *
 * The backend ROTATES refresh tokens and treats presenting an already-used token as theft (it revokes the whole token
 * family and all access tokens of the user). Therefore:
 *   1. refreshes are single-flight inside a tab (concurrent 401s share one in-flight promise);
 *   2. refreshes are serialized across tabs with the Web Locks API, and the refresh token is always re-read from
 *      storage *inside* the lock, so a token rotated by another tab is never presented again;
 *   3. a tab that rotated the token broadcasts the new access token (BroadcastChannel) so other tabs can use it
 *      without refreshing themselves;
 *   4. a refresh token whose request may have reached the server but whose response was lost is discarded
 *      (we cannot know whether it was rotated), so it is never re-sent.
 */

export const REFRESH_TOKEN_KEY = 'pgms.refreshToken';
const LOCK_NAME = 'pgms-auth-refresh';
export const CHANNEL_NAME = 'pgms-auth';
/** Refresh proactively when the access token expires within this window. */
const EXPIRY_SKEW_MS = 30_000;
/** How long to wait for another tab's broadcast after detecting it rotated the refresh token. */
const BROADCAST_WAIT_MS = 1_000;

export class SessionExpiredError extends ApiError {
  constructor(message = 'Your session has expired. Please sign in again.') {
    super({ status: 401, code: 'SESSION_EXPIRED', message });
    this.name = 'SessionExpiredError';
  }
}

interface AccessToken {
  token: string;
  expiresAt: number;
}

type ChannelMessage =
  | { type: 'session'; accessToken: string; expiresAt: number; refreshToken: string; user: UserInfo }
  | { type: 'logout' };

export interface KeyValueStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface LockLike {
  request<T>(name: string, callback: () => Promise<T>): Promise<T>;
}

export interface ChannelLike {
  postMessage(message: unknown): void;
  addEventListener(type: 'message', listener: (event: MessageEvent) => void): void;
  close?(): void;
}

export interface SessionDeps {
  fetch: typeof fetch;
  storage: KeyValueStorage;
  locks?: LockLike;
  channel?: ChannelLike;
  now?: () => number;
  isOnline?: () => boolean;
  baseUrl?: string;
}

export type SessionEvent =
  | { type: 'signed-in'; user: UserInfo }
  | { type: 'refreshed'; user: UserInfo }
  | { type: 'signed-out'; reason: 'logout' | 'expired' | 'other-tab' };

type Listener = (event: SessionEvent) => void;

export class SessionManager {
  private access: AccessToken | null = null;
  private user: UserInfo | null = null;
  /** The refresh token this tab last wrote or received; used to detect rotation by another tab. */
  private knownRefreshToken: string | null;
  private inflight: Promise<string> | null = null;
  private listeners = new Set<Listener>();
  private broadcastWaiters = new Set<(token: string) => void>();
  private readonly deps: Required<Omit<SessionDeps, 'locks' | 'channel'>> & Pick<SessionDeps, 'locks' | 'channel'>;

  constructor(deps: SessionDeps) {
    this.deps = {
      now: () => Date.now(),
      isOnline: () => true,
      baseUrl: '',
      ...deps,
    };
    this.knownRefreshToken = this.readRefreshToken();
    this.deps.channel?.addEventListener('message', (e) => this.onChannelMessage(e.data as ChannelMessage));
  }

  // ---------------------------------------------------------------- queries
  getUser(): UserInfo | null {
    return this.user;
  }

  hasRefreshToken(): boolean {
    return this.readRefreshToken() !== null;
  }

  /** The persisted refresh token (only needed to revoke it on logout). */
  peekRefreshToken(): string | null {
    return this.readRefreshToken();
  }

  /** Current in-memory access token if it is not about to expire. */
  currentAccessToken(): string | null {
    if (!this.access) return null;
    if (this.access.expiresAt - EXPIRY_SKEW_MS <= this.deps.now()) return null;
    return this.access.token;
  }

  /**
   * Returns a usable access token, refreshing first when the in-memory one is missing or about to expire.
   * Returns null when there is no session at all (anonymous request).
   */
  async getValidAccessToken(): Promise<string | null> {
    const current = this.currentAccessToken();
    if (current) return current;
    if (!this.hasRefreshToken()) return null;
    return this.refresh(this.access?.token ?? null);
  }

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  // ---------------------------------------------------------------- mutations
  /** Store a fresh token pair (login). */
  establish(tokens: TokenResponse): void {
    this.apply(tokens);
    this.broadcast(tokens);
    this.emit({ type: 'signed-in', user: tokens.user });
  }

  /** Forget everything locally (does not call the server; see auth/api.ts logout). */
  clear(reason: 'logout' | 'expired' | 'other-tab' = 'logout', notifyOtherTabs = true): void {
    const hadSession = this.user !== null || this.access !== null || this.hasRefreshToken();
    this.access = null;
    this.user = null;
    this.knownRefreshToken = null;
    this.safeStorage(() => this.deps.storage.removeItem(REFRESH_TOKEN_KEY));
    if (notifyOtherTabs) this.post({ type: 'logout' });
    if (hadSession) this.emit({ type: 'signed-out', reason });
  }

  /** Called when another tab removed the refresh token (storage event). */
  handleExternalSignOut(): void {
    if (this.user || this.access) this.clear('other-tab', false);
  }

  /**
   * Single-flight refresh. `failedToken` is the access token that just got a 401 (or null); if another refresh
   * (in this tab or another one) already produced a different token, that one is returned without a new refresh.
   */
  refresh(failedToken: string | null = null): Promise<string> {
    if (this.inflight) return this.inflight;
    const run = () => this.doRefresh(failedToken);
    const p = (this.deps.locks ? this.deps.locks.request(LOCK_NAME, run) : run()).finally(() => {
      this.inflight = null;
    });
    this.inflight = p;
    return p;
  }

  // ---------------------------------------------------------------- internals
  private async doRefresh(failedToken: string | null): Promise<string> {
    // A token newer than the one that failed may have arrived (other tab broadcast / earlier refresh).
    const fresh = this.currentAccessToken();
    if (fresh && fresh !== failedToken) return fresh;

    let refreshToken = this.readRefreshToken();
    if (!refreshToken) {
      this.clear('expired');
      throw new SessionExpiredError();
    }

    if (this.knownRefreshToken !== null && refreshToken !== this.knownRefreshToken) {
      // Another tab rotated the token while we were waiting for the lock. Prefer its broadcast access token.
      const broadcasted = await this.waitForBroadcast(BROADCAST_WAIT_MS);
      if (broadcasted && broadcasted !== failedToken) return broadcasted;
      refreshToken = this.readRefreshToken();
      if (!refreshToken) {
        this.clear('expired');
        throw new SessionExpiredError();
      }
    }

    if (!this.deps.isOnline()) {
      // Do not consume the refresh token while offline; the request would just fail.
      throw new ApiError({ status: 0, code: 'NETWORK_ERROR', message: 'You appear to be offline.' });
    }

    let response: Response;
    try {
      response = await this.deps.fetch(`${this.deps.baseUrl}/api/v1/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ refreshToken }),
        credentials: 'same-origin',
      });
    } catch {
      // The request may have reached the server and rotated the token: never present it again.
      this.clear('expired');
      throw new SessionExpiredError('Connection lost while renewing your session. Please sign in again.');
    }

    if (response.ok) {
      const tokens = (await response.json()) as TokenResponse;
      this.apply(tokens);
      this.broadcast(tokens);
      this.emit({ type: 'refreshed', user: tokens.user });
      return tokens.accessToken;
    }

    const error = await parseErrorResponse(response);
    if (response.status === 429 || response.status === 502 || response.status === 503 || response.status === 504) {
      // Rejected before the token was processed (rate limit / upstream unavailable): keep it for a later attempt.
      throw error;
    }
    // 400/401 (invalid, expired, reused) or anything unexpected: the token is unusable.
    this.clear('expired');
    throw new SessionExpiredError(response.status === 401 ? undefined : error.message);
  }

  private apply(tokens: TokenResponse): void {
    this.access = { token: tokens.accessToken, expiresAt: Date.parse(tokens.accessTokenExpiresAt) };
    this.user = tokens.user;
    this.knownRefreshToken = tokens.refreshToken;
    this.safeStorage(() => this.deps.storage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken));
  }

  private broadcast(tokens: TokenResponse): void {
    this.post({
      type: 'session',
      accessToken: tokens.accessToken,
      expiresAt: Date.parse(tokens.accessTokenExpiresAt),
      refreshToken: tokens.refreshToken,
      user: tokens.user,
    });
  }

  private onChannelMessage(msg: ChannelMessage): void {
    if (!msg || typeof msg !== 'object') return;
    if (msg.type === 'logout') {
      this.handleExternalSignOut();
      return;
    }
    if (msg.type === 'session') {
      const wasSignedIn = this.user !== null;
      this.access = { token: msg.accessToken, expiresAt: msg.expiresAt };
      this.knownRefreshToken = msg.refreshToken;
      const userChanged = this.user?.id !== msg.user.id;
      this.user = msg.user;
      for (const resolve of this.broadcastWaiters) resolve(msg.accessToken);
      this.broadcastWaiters.clear();
      if (!wasSignedIn || userChanged) this.emit({ type: 'signed-in', user: msg.user });
    }
  }

  private waitForBroadcast(timeoutMs: number): Promise<string | null> {
    const current = this.currentAccessToken();
    if (current && this.knownRefreshToken === this.readRefreshToken()) return Promise.resolve(current);
    return new Promise((resolve) => {
      const done = (token: string | null) => {
        clearTimeout(timer);
        this.broadcastWaiters.delete(onToken);
        resolve(token);
      };
      const onToken = (token: string) => done(token);
      const timer = setTimeout(() => done(null), timeoutMs);
      this.broadcastWaiters.add(onToken);
    });
  }

  private readRefreshToken(): string | null {
    try {
      return this.deps.storage.getItem(REFRESH_TOKEN_KEY);
    } catch {
      return null;
    }
  }

  private safeStorage(fn: () => void): void {
    try {
      fn();
    } catch {
      /* storage unavailable (private mode): session simply won't survive a reload */
    }
  }

  private post(msg: ChannelMessage): void {
    try {
      this.deps.channel?.postMessage(msg);
    } catch {
      /* channel closed */
    }
  }

  private emit(event: SessionEvent): void {
    for (const l of this.listeners) l(event);
  }
}
