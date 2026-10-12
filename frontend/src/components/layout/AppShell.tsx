import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, Link } from 'react-router-dom';
import { LogOut, MoreHorizontal, Settings, X } from 'lucide-react';
import { useAuth, useUser } from '@/auth/AuthProvider';
import { NotificationBell } from '@/features/notifications/NotificationBell';
import { ROLE_LABELS, homePath } from '@/lib/roles';
import { cn } from '@/lib/cn';
import { bottomTabsForRole, navForRole, type NavItem } from './nav';
import { Logo } from './Logo';

function SideLink({ item, onNavigate }: { item: NavItem; onNavigate?: () => void }) {
  const Icon = item.icon;
  return (
    <NavLink
      to={item.to}
      end={item.end}
      onClick={onNavigate}
      className={({ isActive }) =>
        cn(
          'flex min-h-touch items-center gap-3 rounded-xl px-3 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500',
          isActive
            ? 'bg-brand-50 text-brand-700 dark:bg-brand-950 dark:text-brand-200'
            : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-slate-100',
        )
      }
    >
      <Icon className="h-5 w-5 shrink-0" aria-hidden />
      {item.label}
    </NavLink>
  );
}

function UserBlock({ compact }: { compact?: boolean }) {
  const user = useUser();
  const initials = user.fullName
    .split(/\s+/)
    .map((p) => p[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();
  return (
    <div className="flex min-w-0 items-center gap-3">
      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-brand-600 text-sm font-semibold text-white" aria-hidden>
        {initials}
      </span>
      {!compact && (
        <span className="min-w-0">
          <span className="block truncate text-sm font-medium text-slate-900 dark:text-slate-100">{user.fullName}</span>
          <span className="block truncate text-xs text-slate-500 dark:text-slate-400">{ROLE_LABELS[user.role]}</span>
        </span>
      )}
    </div>
  );
}

export function AppShell() {
  const user = useUser();
  const { logout } = useAuth();
  const location = useLocation();
  const [moreOpen, setMoreOpen] = useState(false);
  const items = navForRole(user.role);
  const { tabs, more } = bottomTabsForRole(user.role);

  useEffect(() => setMoreOpen(false), [location.pathname]);
  useEffect(() => {
    if (!moreOpen) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setMoreOpen(false);
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [moreOpen]);

  const moreActive = more.some((m) => location.pathname.startsWith(m.to)) || location.pathname.startsWith('/account');

  return (
    <div className="min-h-dvh bg-slate-50 dark:bg-slate-950">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-2 focus:top-2 focus:z-[70] focus:rounded-lg focus:bg-white focus:px-3 focus:py-2 focus:shadow">
        Skip to content
      </a>

      {/* Sidebar (md+) */}
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 flex-col border-r border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900 md:flex" aria-label="Main navigation">
        <div className="flex h-16 items-center px-5">
          <Link to={homePath(user.role)} className="focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 rounded-lg">
            <Logo />
          </Link>
        </div>
        <nav className="flex-1 space-y-1 overflow-y-auto px-3 py-2">
          {items.map((item) => (
            <SideLink key={item.to} item={item} />
          ))}
        </nav>
        <div className="space-y-1 border-t border-slate-200 p-3 dark:border-slate-800">
          <Link to="/account" className="flex min-h-touch items-center rounded-xl px-2 hover:bg-slate-100 dark:hover:bg-slate-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500">
            <UserBlock />
          </Link>
          <button
            type="button"
            onClick={() => void logout()}
            className="flex min-h-touch w-full items-center gap-3 rounded-xl px-3 text-sm font-medium text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
          >
            <LogOut className="h-5 w-5" aria-hidden /> Sign out
          </button>
        </div>
      </aside>

      <div className="md:pl-64">
        {/* Top bar */}
        <header className="sticky top-0 z-20 flex h-14 items-center justify-between gap-2 border-b border-slate-200 bg-white/90 px-4 pt-[env(safe-area-inset-top)] backdrop-blur dark:border-slate-800 dark:bg-slate-900/90 md:h-16 md:px-8">
          <Link to={homePath(user.role)} className="md:hidden" aria-label="Home">
            <Logo />
          </Link>
          <div className="hidden text-sm text-slate-500 dark:text-slate-400 md:block">{ROLE_LABELS[user.role]} workspace</div>
          <div className="flex items-center gap-1">
            <NotificationBell />
            <Link
              to="/account"
              aria-label="Account settings"
              className="inline-flex min-h-touch min-w-touch items-center justify-center rounded-full hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 dark:hover:bg-slate-800 md:hidden"
            >
              <UserBlock compact />
            </Link>
          </div>
        </header>

        <main id="main" className="mx-auto w-full max-w-7xl px-4 pb-[calc(5.5rem+env(safe-area-inset-bottom))] pt-4 md:px-8 md:pb-10 md:pt-6">
          <Outlet />
        </main>
      </div>

      {/* Bottom tab bar (phones) */}
      <nav
        className="fixed inset-x-0 bottom-0 z-30 border-t border-slate-200 bg-white/95 pb-[env(safe-area-inset-bottom)] backdrop-blur dark:border-slate-800 dark:bg-slate-900/95 md:hidden"
        aria-label="Primary"
      >
        <ul className="grid grid-cols-5">
          {tabs.map((item) => {
            const Icon = item.icon;
            return (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) =>
                    cn(
                      'flex h-16 flex-col items-center justify-center gap-1 text-[11px] font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-500',
                      isActive ? 'text-brand-600 dark:text-brand-300' : 'text-slate-500 dark:text-slate-400',
                    )
                  }
                >
                  <Icon className="h-5 w-5" aria-hidden />
                  <span className="max-w-full truncate px-1">{item.shortLabel ?? item.label}</span>
                </NavLink>
              </li>
            );
          })}
          <li>
            <button
              type="button"
              onClick={() => setMoreOpen(true)}
              aria-expanded={moreOpen}
              aria-controls="more-sheet"
              className={cn(
                'flex h-16 w-full flex-col items-center justify-center gap-1 text-[11px] font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-500',
                moreActive ? 'text-brand-600 dark:text-brand-300' : 'text-slate-500 dark:text-slate-400',
              )}
            >
              <MoreHorizontal className="h-5 w-5" aria-hidden />
              More
            </button>
          </li>
        </ul>
      </nav>

      {/* "More" sheet (phones) */}
      {moreOpen && (
        <div className="fixed inset-0 z-40 md:hidden" id="more-sheet" role="dialog" aria-modal="true" aria-label="More navigation">
          <div className="absolute inset-0 bg-slate-950/50" onClick={() => setMoreOpen(false)} aria-hidden />
          <div className="absolute inset-x-0 bottom-0 max-h-[85dvh] overflow-y-auto rounded-t-3xl bg-white p-4 pb-[max(1rem,env(safe-area-inset-bottom))] dark:bg-slate-900">
            <div className="mb-3 flex items-center justify-between">
              <UserBlock />
              <button
                type="button"
                onClick={() => setMoreOpen(false)}
                className="inline-flex min-h-touch min-w-touch items-center justify-center rounded-full hover:bg-slate-100 dark:hover:bg-slate-800"
                aria-label="Close menu"
                autoFocus
              >
                <X className="h-5 w-5" aria-hidden />
              </button>
            </div>
            <nav className="space-y-1" aria-label="More">
              {more.map((item) => (
                <SideLink key={item.to} item={item} onNavigate={() => setMoreOpen(false)} />
              ))}
              <SideLink item={{ to: '/account', label: 'Account & security', icon: Settings, roles: [] }} />
              <button
                type="button"
                onClick={() => void logout()}
                className="flex min-h-touch w-full items-center gap-3 rounded-xl px-3 text-sm font-medium text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-950"
              >
                <LogOut className="h-5 w-5" aria-hidden /> Sign out
              </button>
            </nav>
          </div>
        </div>
      )}
    </div>
  );
}
