import {
  Bell,
  BedDouble,
  Building2,
  CalendarCheck,
  ClipboardList,
  FileText,
  Home,
  LayoutDashboard,
  MessageSquareWarning,
  ScrollText,
  User,
  UserCog,
  Users,
  Wallet,
  Wrench,
  type LucideIcon,
} from 'lucide-react';
import type { Role } from '@/api/types';

export interface NavItem {
  to: string;
  label: string;
  /** Shorter label for the 5-column bottom tab bar on phones. */
  shortLabel?: string;
  icon: LucideIcon;
  roles: readonly Role[];
  /** Match only the exact path for the active state. */
  end?: boolean;
}

const OPS: Role[] = ['SUPER_ADMIN', 'PROPERTY_ADMIN', 'MANAGER'];
const ADMINS: Role[] = ['SUPER_ADMIN', 'PROPERTY_ADMIN'];
const STAFFISH: Role[] = ['SUPER_ADMIN', 'PROPERTY_ADMIN', 'MANAGER', 'STAFF'];
const ALL: Role[] = ['SUPER_ADMIN', 'PROPERTY_ADMIN', 'MANAGER', 'STAFF', 'TENANT'];

export const NAV_ITEMS: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard, roles: OPS },
  { to: '/work', label: 'Work queue', shortLabel: 'Queue', icon: ClipboardList, roles: ['STAFF'] },
  { to: '/me', label: 'Home', icon: Home, roles: ['TENANT'], end: true },
  { to: '/me/bills', label: 'Bills', icon: Wallet, roles: ['TENANT'] },
  { to: '/beds', label: 'Bed board', shortLabel: 'Beds', icon: BedDouble, roles: STAFFISH },
  { to: '/tenants', label: 'Tenants', icon: Users, roles: STAFFISH },
  { to: '/invoices', label: 'Billing', icon: FileText, roles: OPS },
  { to: '/payments', label: 'Payments', icon: Wallet, roles: OPS },
  { to: '/bookings', label: 'Bookings', icon: CalendarCheck, roles: OPS },
  { to: '/maintenance', label: 'Maintenance', shortLabel: 'Repairs', icon: Wrench, roles: ALL },
  { to: '/complaints', label: 'Complaints', icon: MessageSquareWarning, roles: ALL },
  { to: '/properties', label: 'Properties', icon: Building2, roles: STAFFISH },
  { to: '/notifications', label: 'Notifications', icon: Bell, roles: ALL },
  { to: '/users', label: 'Users', icon: UserCog, roles: ADMINS },
  { to: '/audit', label: 'Audit log', icon: ScrollText, roles: ADMINS },
  { to: '/me/profile', label: 'My profile', icon: User, roles: ['TENANT'] },
];

/** Bottom tab bar on phones: 4 role-specific destinations + "More". */
const BOTTOM_TABS: Record<Role, string[]> = {
  SUPER_ADMIN: ['/dashboard', '/beds', '/tenants', '/invoices'],
  PROPERTY_ADMIN: ['/dashboard', '/beds', '/tenants', '/invoices'],
  MANAGER: ['/dashboard', '/beds', '/tenants', '/invoices'],
  STAFF: ['/work', '/maintenance', '/complaints', '/beds'],
  TENANT: ['/me', '/me/bills', '/maintenance', '/complaints'],
};

export function navForRole(role: Role): NavItem[] {
  return NAV_ITEMS.filter((i) => i.roles.includes(role));
}

export function bottomTabsForRole(role: Role): { tabs: NavItem[]; more: NavItem[] } {
  const items = navForRole(role);
  const paths = BOTTOM_TABS[role];
  const tabs = paths.map((p) => items.find((i) => i.to === p)).filter((i): i is NavItem => !!i);
  const more = items.filter((i) => !paths.includes(i.to));
  return { tabs, more };
}
