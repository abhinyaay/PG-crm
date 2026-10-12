import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { session } from '@/api';
import type { Role } from '@/api/types';
import { AuthProvider } from '@/auth/AuthProvider';
import { RequireRole } from '@/auth/guards';
import { ToastProvider } from '@/components/ui/Toast';
import { demoUser, jsonResponse, tokens } from '@/test/utils';
import { AppShell } from './AppShell';
import { bottomTabsForRole, navForRole } from './nav';

describe('navForRole', () => {
  const labels = (role: Role) => navForRole(role).map((i) => i.label);

  it('gives admins the full operator menu including users and audit', () => {
    expect(labels('PROPERTY_ADMIN')).toEqual(expect.arrayContaining(['Dashboard', 'Bed board', 'Tenants', 'Billing', 'Payments', 'Users', 'Audit log']));
  });

  it('hides admin-only sections from managers', () => {
    expect(labels('MANAGER')).toContain('Billing');
    expect(labels('MANAGER')).not.toContain('Users');
    expect(labels('MANAGER')).not.toContain('Audit log');
  });

  it('gives staff a work queue and no billing', () => {
    expect(labels('STAFF')).toContain('Work queue');
    expect(labels('STAFF')).not.toContain('Billing');
    expect(labels('STAFF')).not.toContain('Dashboard');
  });

  it('gives tenants only the portal', () => {
    expect(labels('TENANT')).toEqual(['Home', 'Bills', 'Maintenance', 'Complaints', 'Notifications', 'My profile']);
  });

  it('puts four role-specific tabs in the bottom bar and the rest under More', () => {
    const { tabs, more } = bottomTabsForRole('TENANT');
    expect(tabs.map((t) => t.label)).toEqual(['Home', 'Bills', 'Maintenance', 'Complaints']);
    expect(more.map((t) => t.label)).toEqual(['Notifications', 'My profile']);
    expect(bottomTabsForRole('STAFF').tabs.map((t) => t.to)).toEqual(['/work', '/maintenance', '/complaints', '/beds']);
  });
});

function renderShell(role: Role, path = '/') {
  session.establish(tokens(1, { ...demoUser, role, fullName: 'Test Person' }));
  vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ unread: 3 })));
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <AuthProvider>
          <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
            <Routes>
              <Route element={<AppShell />}>
                <Route path="/" element={<p>home</p>} />
                <Route
                  path="/users"
                  element={
                    <RequireRole roles={['SUPER_ADMIN', 'PROPERTY_ADMIN']}>
                      <p>users page</p>
                    </RequireRole>
                  }
                />
                <Route path="/me" element={<p>tenant home</p>} />
              </Route>
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

describe('<AppShell> role-based navigation', () => {
  afterEach(() => {
    cleanup(); // unmount before clearing the session so no state update hits an unmounted tree
    vi.unstubAllGlobals();
    session.clear('logout', false);
  });

  it('renders the admin sidebar and bottom tabs', async () => {
    renderShell('PROPERTY_ADMIN');
    const sidebar = screen.getByRole('complementary', { name: /main navigation/i });
    expect(within(sidebar).getByRole('link', { name: 'Users' })).toBeInTheDocument();
    expect(within(sidebar).getByRole('link', { name: 'Audit log' })).toBeInTheDocument();
    const bottom = screen.getByRole('navigation', { name: 'Primary' });
    expect(within(bottom).getAllByRole('link').map((a) => a.textContent)).toEqual(['Dashboard', 'Beds', 'Tenants', 'Billing']);
    expect(await screen.findByRole('link', { name: 'Notifications, 3 unread' })).toBeInTheDocument();
  });

  it('renders the tenant portal navigation without operator sections', () => {
    renderShell('TENANT');
    const sidebar = screen.getByRole('complementary', { name: /main navigation/i });
    expect(within(sidebar).getByRole('link', { name: 'Bills' })).toBeInTheDocument();
    expect(within(sidebar).queryByRole('link', { name: 'Tenants' })).not.toBeInTheDocument();
    expect(within(sidebar).queryByRole('link', { name: 'Billing' })).not.toBeInTheDocument();
  });

  it('redirects a role without access away from an admin route', () => {
    renderShell('TENANT', '/users');
    expect(screen.queryByText('users page')).not.toBeInTheDocument();
    expect(screen.getByText('tenant home')).toBeInTheDocument();
  });

  it('lets admins open the admin route', () => {
    renderShell('PROPERTY_ADMIN', '/users');
    expect(screen.getByText('users page')).toBeInTheDocument();
  });
});
