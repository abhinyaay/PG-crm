import type { Role } from '@/api/types';

export const ADMIN_ROLES: readonly Role[] = ['SUPER_ADMIN', 'PROPERTY_ADMIN'];
/** Roles that run properties day to day (dashboards, billing, bookings). */
export const OPERATOR_ROLES: readonly Role[] = ['SUPER_ADMIN', 'PROPERTY_ADMIN', 'MANAGER'];
/** Operators plus on-site staff. */
export const STAFF_ROLES: readonly Role[] = ['SUPER_ADMIN', 'PROPERTY_ADMIN', 'MANAGER', 'STAFF'];
export const ANALYTICS_ROLES: readonly Role[] = OPERATOR_ROLES;

export function hasRole(role: Role | undefined | null, allowed: readonly Role[]): boolean {
  return !!role && allowed.includes(role);
}

export const isAdmin = (role?: Role | null) => hasRole(role, ADMIN_ROLES);
export const isOperator = (role?: Role | null) => hasRole(role, OPERATOR_ROLES);
export const isStaffOrAbove = (role?: Role | null) => hasRole(role, STAFF_ROLES);

export const ROLE_LABELS: Record<Role, string> = {
  SUPER_ADMIN: 'Super admin',
  PROPERTY_ADMIN: 'Property admin',
  MANAGER: 'Manager',
  STAFF: 'Staff',
  TENANT: 'Resident',
};

/** Landing page per role. */
export function homePath(role: Role): string {
  switch (role) {
    case 'TENANT':
      return '/me';
    case 'STAFF':
      return '/work';
    default:
      return '/dashboard';
  }
}
