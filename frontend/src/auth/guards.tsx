import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import type { Role } from '@/api/types';
import { homePath } from '@/lib/roles';
import { useAuth } from './AuthProvider';

export function FullPageSpinner() {
  return (
    <div className="flex min-h-dvh items-center justify-center" role="status">
      <span className="h-8 w-8 animate-spin rounded-full border-2 border-slate-300 border-t-brand-600" aria-hidden />
      <span className="sr-only">Loading…</span>
    </div>
  );
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const { status, explicitSignOut } = useAuth();
  const location = useLocation();
  if (status === 'loading') return <FullPageSpinner />;
  if (status === 'anonymous') {
    const next = explicitSignOut ? '' : location.pathname + location.search;
    return <Navigate to={`/login${next && next !== '/' ? `?next=${encodeURIComponent(next)}` : ''}`} replace />;
  }
  return <>{children}</>;
}

/** Route-level role gate; users without the role land on their own home page. */
export function RequireRole({ roles, children }: { roles: readonly Role[]; children: ReactNode }) {
  const { user } = useAuth();
  if (!user) return null;
  if (!roles.includes(user.role)) return <Navigate to={homePath(user.role)} replace />;
  return <>{children}</>;
}

export function HomeRedirect() {
  const { user } = useAuth();
  return <Navigate to={user ? homePath(user.role) : '/login'} replace />;
}
