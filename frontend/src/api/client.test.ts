import { describe, expect, it, vi } from 'vitest';
import { ApiClient, buildQuery } from './client';
import { ApiError, applyFieldErrors, parseErrorResponse, toFormPath } from './errors';
import { REFRESH_TOKEN_KEY, SessionExpiredError, SessionManager } from './session';
import { channelBus, demoUser, fakeLocks, jsonResponse, memoryStorage, tokens } from '@/test/utils';

function authHeader(init?: RequestInit): string | undefined {
  return (init?.headers as Record<string, string> | undefined)?.Authorization;
}

/** Backend fake: rotates refresh tokens and rejects reuse, like AuthService.refresh. */
function fakeBackend() {
  let generation = 1;
  const validRefresh = new Set(['refresh-1']);
  const used = new Set<string>();
  const refreshBodies: string[] = [];
  const fetchMock = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
    const path = String(url);
    if (path.endsWith('/api/v1/auth/refresh')) {
      const { refreshToken } = JSON.parse(String(init?.body)) as { refreshToken: string };
      refreshBodies.push(refreshToken);
      await new Promise((r) => setTimeout(r, 5));
      if (used.has(refreshToken) || !validRefresh.has(refreshToken)) {
        return jsonResponse({ status: 401, code: 'INVALID_REFRESH_TOKEN', message: 'Invalid refresh token' }, 401);
      }
      used.add(refreshToken);
      validRefresh.delete(refreshToken);
      generation += 1;
      validRefresh.add(`refresh-${generation}`);
      return jsonResponse(tokens(generation));
    }
    // protected resource: only the latest access token is accepted
    if (authHeader(init) === `Bearer access-${generation}`) return jsonResponse({ ok: true, path });
    return jsonResponse({ status: 401, code: 'UNAUTHORIZED', message: 'Missing, invalid, expired or revoked access token', correlationId: 'c-401' }, 401);
  });
  return { fetchMock, refreshBodies, get generation() { return generation; } };
}

function setup(opts: { locks?: boolean } = {}) {
  const backend = fakeBackend();
  const storage = memoryStorage({ [REFRESH_TOKEN_KEY]: 'refresh-1' });
  const session = new SessionManager({ fetch: backend.fetchMock as unknown as typeof fetch, storage, locks: opts.locks === false ? undefined : fakeLocks() });
  const client = new ApiClient(session, backend.fetchMock as unknown as typeof fetch);
  return { backend, storage, session, client };
}

describe('ApiClient + SessionManager: refresh', () => {
  it('restores a session from the persisted refresh token before the first request', async () => {
    const { client, backend, storage } = setup();
    await expect(client.get('/api/v1/tenants')).resolves.toEqual({ ok: true, path: '/api/v1/tenants' });
    expect(backend.refreshBodies).toEqual(['refresh-1']);
    expect(storage.getItem(REFRESH_TOKEN_KEY)).toBe('refresh-2');
  });

  it('single-flight: concurrent 401s trigger exactly one refresh and every request is retried with the new token', async () => {
    const { client, backend, session } = setup();
    // signed in with a token the server no longer accepts
    session.establish({ ...tokens(1), accessToken: 'stale-access' });
    const results = await Promise.all([client.get('/api/v1/a'), client.get('/api/v1/b'), client.get('/api/v1/c'), client.get('/api/v1/d')]);
    expect(results).toHaveLength(4);
    expect(backend.refreshBodies).toEqual(['refresh-1']);
    const retried = backend.fetchMock.mock.calls.filter(([, init]) => authHeader(init) === 'Bearer access-2');
    expect(retried).toHaveLength(4);
  });

  it('never presents the same refresh token twice, even across tabs sharing storage', async () => {
    const backend = fakeBackend();
    const storage = memoryStorage({ [REFRESH_TOKEN_KEY]: 'refresh-1' });
    const locks = fakeLocks();
    const bus = channelBus();
    const tabA = new SessionManager({ fetch: backend.fetchMock as unknown as typeof fetch, storage, locks, channel: bus() });
    const tabB = new SessionManager({ fetch: backend.fetchMock as unknown as typeof fetch, storage, locks, channel: bus() });
    const clientA = new ApiClient(tabA, backend.fetchMock as unknown as typeof fetch);
    const clientB = new ApiClient(tabB, backend.fetchMock as unknown as typeof fetch);

    await Promise.all([clientA.get('/api/v1/x'), clientB.get('/api/v1/y'), clientA.get('/api/v1/z')]);
    // every refresh request carried a distinct token and none was rejected as reuse
    expect(new Set(backend.refreshBodies).size).toBe(backend.refreshBodies.length);
    expect(backend.refreshBodies[0]).toBe('refresh-1');
    expect(tabA.getUser()).toEqual(demoUser);
    expect(tabB.getUser()).toEqual(demoUser);
  });

  it('a tab that finds the token rotated by another tab uses the broadcast access token instead of refreshing', async () => {
    const backend = fakeBackend();
    const storage = memoryStorage({ [REFRESH_TOKEN_KEY]: 'refresh-1' });
    const bus = channelBus();
    const locks = fakeLocks();
    const tabA = new SessionManager({ fetch: backend.fetchMock as unknown as typeof fetch, storage, locks, channel: bus() });
    const tabB = new SessionManager({ fetch: backend.fetchMock as unknown as typeof fetch, storage, locks, channel: bus() });
    await tabA.refresh();
    await new Promise((r) => setTimeout(r, 10)); // let the broadcast arrive
    await expect(tabB.getValidAccessToken()).resolves.toBe('access-2');
    expect(backend.refreshBodies).toEqual(['refresh-1']);
  });

  it('clears the session when the refresh token is rejected and surfaces SessionExpiredError', async () => {
    const { client, session, storage } = setup();
    storage.setItem(REFRESH_TOKEN_KEY, 'refresh-unknown');
    const events: string[] = [];
    session.subscribe((e) => events.push(e.type));
    session.establish({ ...tokens(1), accessToken: 'stale', refreshToken: 'refresh-unknown' });
    await expect(client.get('/api/v1/tenants')).rejects.toBeInstanceOf(SessionExpiredError);
    expect(storage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
    expect(events).toContain('signed-out');
  });

  it('drops the refresh token after a network failure during refresh (it may have been rotated server-side)', async () => {
    const storage = memoryStorage({ [REFRESH_TOKEN_KEY]: 'refresh-1' });
    const fetchMock = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    });
    const session = new SessionManager({ fetch: fetchMock as unknown as typeof fetch, storage });
    await expect(session.refresh()).rejects.toBeInstanceOf(SessionExpiredError);
    expect(storage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
    await expect(session.refresh()).rejects.toBeInstanceOf(SessionExpiredError);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('keeps the refresh token when refresh is rate limited (429), so it can be retried later', async () => {
    const storage = memoryStorage({ [REFRESH_TOKEN_KEY]: 'refresh-1' });
    const fetchMock = vi.fn(async () => jsonResponse({ status: 429, code: 'RATE_LIMITED', message: 'Too many requests' }, 429));
    const session = new SessionManager({ fetch: fetchMock as unknown as typeof fetch, storage });
    await expect(session.refresh()).rejects.toMatchObject({ status: 429 });
    expect(storage.getItem(REFRESH_TOKEN_KEY)).toBe('refresh-1');
  });

  it('does not refresh when offline', async () => {
    const storage = memoryStorage({ [REFRESH_TOKEN_KEY]: 'refresh-1' });
    const fetchMock = vi.fn();
    const session = new SessionManager({ fetch: fetchMock as unknown as typeof fetch, storage, isOnline: () => false });
    await expect(session.refresh()).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
    expect(fetchMock).not.toHaveBeenCalled();
    expect(storage.getItem(REFRESH_TOKEN_KEY)).toBe('refresh-1');
  });

  it('does not treat a business 401 (wrong current password) as an expired session', async () => {
    const storage = memoryStorage({ [REFRESH_TOKEN_KEY]: 'refresh-1' });
    const fetchMock = vi.fn(async (url: RequestInfo | URL) =>
      String(url).endsWith('/change-password')
        ? jsonResponse({ status: 401, code: 'INVALID_CREDENTIALS', message: 'Current password is incorrect' }, 401)
        : jsonResponse(tokens(2)),
    );
    const session = new SessionManager({ fetch: fetchMock as unknown as typeof fetch, storage });
    session.establish(tokens(1));
    const client = new ApiClient(session, fetchMock as unknown as typeof fetch);
    await expect(client.post('/api/v1/auth/change-password', {})).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(session.getUser()).toEqual(demoUser);
  });
});

describe('error mapping', () => {
  it('parses the error envelope including field errors and correlation id', async () => {
    const res = jsonResponse(
      {
        status: 400,
        code: 'VALIDATION_FAILED',
        message: 'Request validation failed',
        correlationId: 'abc123',
        fieldErrors: [{ field: 'phone', message: 'must be 10-15 digits' }],
      },
      400,
    );
    const err = await parseErrorResponse(res);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 400, code: 'VALIDATION_FAILED', message: 'Request validation failed', correlationId: 'abc123' });
    expect(err.fieldErrors).toEqual([{ field: 'phone', message: 'must be 10-15 digits' }]);
  });

  it('falls back to the X-Correlation-Id header and a friendly message for non-JSON bodies', async () => {
    const res = new Response('<html>502 Bad Gateway</html>', { status: 502, headers: { 'X-Correlation-Id': 'hdr-1' } });
    const err = await parseErrorResponse(res);
    expect(err).toMatchObject({ status: 502, code: 'HTTP_502', correlationId: 'hdr-1' });
    expect(err.message).toMatch(/temporarily unavailable/i);
  });

  it('turns fetch failures into NETWORK_ERROR ApiErrors', async () => {
    const session = new SessionManager({ fetch: vi.fn() as unknown as typeof fetch, storage: memoryStorage() });
    const client = new ApiClient(session, vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    }) as unknown as typeof fetch);
    await expect(client.get('/api/v1/x')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' });
  });

  it('maps backend field paths onto react-hook-form paths and reports unknown fields', () => {
    expect(toFormPath('emergencyContacts[0].phone')).toBe('emergencyContacts.0.phone');
    const setError = vi.fn();
    const err = new ApiError({
      status: 400,
      code: 'VALIDATION_FAILED',
      message: 'Request validation failed',
      fieldErrors: [
        { field: 'address.city', message: 'must not be blank' },
        { field: 'emergencyContacts[1].phone', message: 'must be 10-15 digits' },
        { field: 'mystery', message: 'nope' },
      ],
    });
    const unmapped = applyFieldErrors(err, setError, ['address', 'emergencyContacts']);
    expect(setError).toHaveBeenCalledWith('address.city', { type: 'server', message: 'must not be blank' }, { shouldFocus: true });
    expect(setError).toHaveBeenCalledWith('emergencyContacts.1.phone', { type: 'server', message: 'must be 10-15 digits' }, { shouldFocus: false });
    expect(unmapped).toEqual([{ field: 'mystery', message: 'nope' }]);
  });

  it('builds query strings skipping empty values and repeating arrays', () => {
    expect(buildQuery({ a: 1, b: '', c: undefined, d: null, sort: ['name,asc', 'id,desc'], flag: false })).toBe('?a=1&sort=name%2Casc&sort=id%2Cdesc&flag=false');
    expect(buildQuery({})).toBe('');
  });
});
