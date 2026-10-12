import fs from 'node:fs/promises';
import { request, type BrowserContext, type BrowserContextOptions } from '@playwright/test';
import { ACCOUNTS, BASE_URL, DEMO_PASSWORD, REFRESH_TOKEN_KEY, statePath, type RoleName } from './env';

/**
 * Why a broker?
 *
 * The SPA keeps the access token in memory only, so every page load restores the session by POSTing the refresh
 * token from localStorage (`pgms.refreshToken`) to `/api/v1/auth/refresh` (frontend/src/api/session.ts). The backend
 * ROTATES refresh tokens and treats a replayed one as theft (it revokes the whole family and every access token of
 * the user), and `/auth/login` + `/auth/refresh` share a limit of ~20 requests/min per IP. So a suite that simply
 * reuses a saved storageState in many fresh contexts would (a) replay the same refresh token and get every session
 * revoked, and (b) burn the rate limit with one refresh per page load.
 *
 * The broker owns each role's token family for the whole run:
 *  - The setup project signs in ONCE per role through the real login form and saves the storageState.
 *  - The first time a worker needs a role it performs ONE real refresh with the stored token, under a cross-process
 *    file lock, and writes the rotated refresh token back into the storageState file. The chain therefore continues
 *    across workers / projects / retries and a refresh token is never presented twice.
 *  - Browser contexts get the CURRENT refresh token in localStorage, and their `/auth/refresh` calls are answered
 *    from the broker's cache (`route.fulfill`). The SPA's restore code runs unchanged (read storage, call refresh,
 *    apply the token pair); only the server hop is served locally until the access token is close to expiry, when
 *    the broker rotates for real again.
 *  - `/auth/logout(-all)` from a test page is answered locally so an accidental "Sign out" click cannot revoke the
 *    shared family for the remaining tests.
 *
 * Net cost per run: 4 logins (setup) + at most 4 refreshes per worker (+1 per role every ~14 minutes).
 */

export interface UserInfo {
  id: number;
  email: string;
  fullName: string;
  role: string;
  organizationId: number;
}

export interface TokenResponse {
  tokenType: string;
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  user: UserInfo;
}

/** Shape accepted by `browser.newContext({ storageState })`. */
type StorageState = Exclude<NonNullable<BrowserContextOptions['storageState']>, string>;

/** Renew the access token when it expires within this window. */
const RENEW_BEFORE_MS = 2 * 60_000;
const LOCK_STALE_MS = 30_000;
const LOCK_TIMEOUT_MS = 30_000;

const origin = new URL(BASE_URL).origin;

async function withFileLock<T>(lockDir: string, fn: () => Promise<T>): Promise<T> {
  const started = Date.now();
  for (;;) {
    try {
      await fs.mkdir(lockDir);
      break;
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code !== 'EEXIST') throw e;
      const stat = await fs.stat(lockDir).catch(() => null);
      if (stat && Date.now() - stat.mtimeMs > LOCK_STALE_MS) {
        await fs.rm(lockDir, { recursive: true, force: true });
        continue;
      }
      if (Date.now() - started > LOCK_TIMEOUT_MS) throw new Error(`Timed out waiting for ${lockDir}`);
      // another worker is rotating this role's token right now; it finishes within one HTTP round trip
      await new Promise((r) => setTimeout(r, 50));
    }
  }
  try {
    return await fn();
  } finally {
    await fs.rm(lockDir, { recursive: true, force: true });
  }
}

async function readState(role: RoleName): Promise<StorageState> {
  try {
    return JSON.parse(await fs.readFile(statePath(role), 'utf8')) as StorageState;
  } catch {
    throw new Error(`Missing ${statePath(role)} - run the "setup" project first (npm run test:e2e does this automatically).`);
  }
}

function refreshTokenOf(state: StorageState): string | null {
  const o = state.origins.find((x) => x.origin === origin);
  return o?.localStorage.find((i) => i.name === REFRESH_TOKEN_KEY)?.value ?? null;
}

export function stateWithRefreshToken(refreshToken: string, base?: StorageState): StorageState {
  const others = (base?.origins ?? []).filter((o) => o.origin !== origin);
  return { cookies: base?.cookies ?? [], origins: [...others, { origin, localStorage: [{ name: REFRESH_TOKEN_KEY, value: refreshToken }] }] };
}

export class SessionBroker {
  private cache = new Map<RoleName, TokenResponse>();
  private inflight = new Map<RoleName, Promise<TokenResponse>>();
  /** Real calls to /auth/login or /auth/refresh made by this worker (for the run summary / debugging). */
  realAuthCalls = 0;

  /** A token pair whose access token is valid for at least a couple of minutes. */
  async tokens(role: RoleName): Promise<TokenResponse> {
    const cached = this.cache.get(role);
    if (cached && Date.parse(cached.accessTokenExpiresAt) - Date.now() > RENEW_BEFORE_MS) return cached;
    let p = this.inflight.get(role);
    if (!p) {
      p = this.renew(role).finally(() => this.inflight.delete(role));
      this.inflight.set(role, p);
    }
    return p;
  }

  /** Install the stored session into a fresh browser context. */
  async attach(context: BrowserContext, role: RoleName): Promise<void> {
    await context.route('**/api/v1/auth/refresh', async (route) => {
      const t = await this.tokens(role);
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(t) });
    });
    await context.route(/\/api\/v1\/auth\/logout(-all)?$/, (route) => route.fulfill({ status: 204 }));
  }

  async storageState(role: RoleName): Promise<StorageState> {
    const t = await this.tokens(role);
    return stateWithRefreshToken(t.refreshToken);
  }

  private async renew(role: RoleName): Promise<TokenResponse> {
    return withFileLock(`${statePath(role)}.lock`, async () => {
      // re-read inside the lock: another worker may have rotated the token a moment ago
      const state = await readState(role);
      const stored = refreshTokenOf(state);
      const api = await request.newContext({ baseURL: BASE_URL });
      try {
        let res = stored ? await api.post('/api/v1/auth/refresh', { data: { refreshToken: stored } }) : null;
        this.realAuthCalls += res ? 1 : 0;
        if (!res || res.status() === 400 || res.status() === 401) {
          // the chain is broken (e.g. the family was revoked outside the suite): start a new one with a single login
          res = await api.post('/api/v1/auth/login', { data: { email: ACCOUNTS[role].email, password: DEMO_PASSWORD } });
          this.realAuthCalls += 1;
        }
        if (!res.ok()) throw new Error(`Could not obtain a ${role} session: HTTP ${res.status()} ${await res.text()}`);
        const tokens = (await res.json()) as TokenResponse;
        await fs.writeFile(statePath(role), JSON.stringify(stateWithRefreshToken(tokens.refreshToken, state), null, 2));
        this.cache.set(role, tokens);
        return tokens;
      } finally {
        await api.dispose();
      }
    });
  }
}
