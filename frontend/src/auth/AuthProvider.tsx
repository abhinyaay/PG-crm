import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, session } from '@/api';
import type { TokenResponse, UserInfo } from '@/api/types';
import { SessionExpiredError } from '@/api/session';

type Status = 'loading' | 'authenticated' | 'anonymous';

interface AuthContextValue {
  status: Status;
  user: UserInfo | null;
  /** Why the user was signed out (shown on the login page). */
  signOutReason: string | null;
  /** True after the user signed out themselves (no "return to previous page" for the next sign-in). */
  explicitSignOut: boolean;
  login: (email: string, password: string) => Promise<UserInfo>;
  logout: () => Promise<void>;
  logoutEverywhere: () => Promise<void>;
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [user, setUser] = useState<UserInfo | null>(() => session.getUser());
  const [status, setStatus] = useState<Status>(() => (session.getUser() ? 'authenticated' : session.hasRefreshToken() ? 'loading' : 'anonymous'));
  const [signOutReason, setSignOutReason] = useState<string | null>(null);
  const [explicitSignOut, setExplicitSignOut] = useState(false);
  const userIdRef = useRef<number | null>(user?.id ?? null);

  useEffect(() => {
    const unsubscribe = session.subscribe((event) => {
      if (event.type === 'signed-out') {
        userIdRef.current = null;
        setUser(null);
        setStatus('anonymous');
        queryClient.clear();
        if (event.reason === 'expired') setSignOutReason('Your session has expired. Please sign in again.');
        else if (event.reason === 'other-tab') setSignOutReason('You were signed out in another tab.');
        return;
      }
      if (userIdRef.current !== null && userIdRef.current !== event.user.id) queryClient.clear();
      userIdRef.current = event.user.id;
      setExplicitSignOut(false);
      setUser(event.user);
      setStatus('authenticated');
      setSignOutReason(null);
    });

    // Restore the session after a reload: exchange the persisted refresh token for an access token.
    if (!session.getUser() && session.hasRefreshToken()) {
      session.refresh().catch((e: unknown) => {
        if (!(e instanceof SessionExpiredError)) {
          // transient (offline / 429 / 503): keep the refresh token, but show the login screen for now
          setSignOutReason(e instanceof Error ? e.message : 'Could not restore your session.');
        }
        setStatus(session.getUser() ? 'authenticated' : 'anonymous');
      });
    }
    return unsubscribe;
  }, [queryClient]);

  const login = useCallback(async (email: string, password: string) => {
    const tokens = await api.post<TokenResponse>('/api/v1/auth/login', { email, password }, { auth: false, retryOn401: false });
    session.establish(tokens);
    return tokens.user;
  }, []);

  const logout = useCallback(async () => {
    try {
      // make sure we hold a valid access token first, so the refresh token we revoke is the current one
      await session.getValidAccessToken();
      const refreshToken = session.peekRefreshToken();
      if (refreshToken) await api.post('/api/v1/auth/logout', { refreshToken }, { responseType: 'none', retryOn401: false });
    } catch {
      /* best effort: the local session is cleared regardless */
    } finally {
      setExplicitSignOut(true);
      session.clear('logout');
      setSignOutReason(null);
    }
  }, []);

  const logoutEverywhere = useCallback(async () => {
    try {
      await api.post('/api/v1/auth/logout-all', undefined, { responseType: 'none' });
    } finally {
      setExplicitSignOut(true);
      session.clear('logout');
      setSignOutReason('You were signed out on all devices.');
    }
  }, []);

  const changePassword = useCallback(async (currentPassword: string, newPassword: string) => {
    await api.post('/api/v1/auth/change-password', { currentPassword, newPassword }, { responseType: 'none' });
    // the backend revokes every session of the user after a password change
    setExplicitSignOut(true);
    session.clear('logout');
    setSignOutReason('Password changed. Please sign in with your new password.');
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ status, user, signOutReason, explicitSignOut, login, logout, logoutEverywhere, changePassword }),
    [status, user, signOutReason, explicitSignOut, login, logout, logoutEverywhere, changePassword],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}

/** Signed-in user (only call below <RequireAuth>). */
export function useUser(): UserInfo {
  const { user } = useAuth();
  if (!user) throw new Error('useUser called without a signed-in user');
  return user;
}
