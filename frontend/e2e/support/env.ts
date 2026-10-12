import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** The running stack (edge nginx: SPA at `/`, API at `/api/v1`, analytics at `/analytics`). */
export const BASE_URL = (process.env.E2E_BASE_URL ?? 'http://localhost:58088').replace(/\/$/, '');

/** Shared password of the seeded demo accounts (see the root README, "Demo data"). */
export const DEMO_PASSWORD = process.env.E2E_DEMO_PASSWORD ?? 'Demo12345';

/** localStorage key holding the refresh token (frontend/src/api/session.ts). */
export const REFRESH_TOKEN_KEY = 'pgms.refreshToken';

export type RoleName = 'owner' | 'manager' | 'staff' | 'tenant';
export const ROLES: readonly RoleName[] = ['owner', 'manager', 'staff', 'tenant'];

export interface Account {
  email: string;
  role: 'PROPERTY_ADMIN' | 'MANAGER' | 'STAFF' | 'TENANT';
  fullName: string;
  /** Landing page after sign-in. */
  home: string;
  /** H1 on the landing page. */
  homeHeading: string;
}

export const ACCOUNTS: Record<RoleName, Account> = {
  owner: { email: 'owner@sunrise.example.com', role: 'PROPERTY_ADMIN', fullName: 'Sunrise Owner', home: '/dashboard', homeHeading: 'Dashboard' },
  manager: { email: 'manager.blr@sunrise.example.com', role: 'MANAGER', fullName: 'Ravi Manager', home: '/dashboard', homeHeading: 'Dashboard' },
  staff: { email: 'staff1@sunrise.example.com', role: 'STAFF', fullName: 'Suresh Technician', home: '/work', homeHeading: 'Hi, Suresh' },
  tenant: { email: 'tenant.demo@example.com', role: 'TENANT', fullName: 'Demo Tenant', home: '/me', homeHeading: 'Hello, Demo' },
};

/** Property of the demo tenant; manager.blr and staff1 are both assigned to it. */
export const DEMO_PROPERTY = 'Sunrise Koramangala';

const here = path.dirname(fileURLToPath(import.meta.url));
export const AUTH_DIR = path.resolve(here, '..', '.auth');
export const statePath = (role: RoleName) => path.join(AUTH_DIR, `${role}.json`);
