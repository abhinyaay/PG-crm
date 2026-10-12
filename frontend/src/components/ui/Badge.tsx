import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { humanize } from '@/lib/format';

export type Tone = 'gray' | 'green' | 'blue' | 'amber' | 'red' | 'violet' | 'sky';

const tones: Record<Tone, string> = {
  gray: 'bg-slate-100 text-slate-700 ring-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:ring-slate-700',
  green: 'bg-emerald-50 text-emerald-700 ring-emerald-200 dark:bg-emerald-950 dark:text-emerald-300 dark:ring-emerald-900',
  blue: 'bg-brand-50 text-brand-700 ring-brand-200 dark:bg-brand-950 dark:text-brand-200 dark:ring-brand-900',
  amber: 'bg-amber-50 text-amber-800 ring-amber-200 dark:bg-amber-950 dark:text-amber-300 dark:ring-amber-900',
  red: 'bg-red-50 text-red-700 ring-red-200 dark:bg-red-950 dark:text-red-300 dark:ring-red-900',
  violet: 'bg-violet-50 text-violet-700 ring-violet-200 dark:bg-violet-950 dark:text-violet-300 dark:ring-violet-900',
  sky: 'bg-sky-50 text-sky-700 ring-sky-200 dark:bg-sky-950 dark:text-sky-300 dark:ring-sky-900',
};

export function Badge({ tone = 'gray', children, className, icon }: { tone?: Tone; children: ReactNode; className?: string; icon?: ReactNode }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
        tones[tone],
        className,
      )}
    >
      {icon}
      {children}
    </span>
  );
}

const STATUS_TONES: Record<string, Tone> = {
  // generic
  ACTIVE: 'green',
  INACTIVE: 'gray',
  SUSPENDED: 'red',
  UNDER_RENOVATION: 'amber',
  // beds
  AVAILABLE: 'green',
  OCCUPIED: 'blue',
  RESERVED: 'amber',
  MAINTENANCE: 'red',
  // tenants
  PROSPECTIVE: 'sky',
  ON_NOTICE: 'amber',
  MOVED_OUT: 'gray',
  // bookings / allocations
  CHECKED_IN: 'green',
  CANCELLED: 'gray',
  EXPIRED: 'gray',
  ENDED: 'gray',
  TRANSFERRED: 'violet',
  // invoices / payments
  ISSUED: 'blue',
  PARTIALLY_PAID: 'amber',
  PAID: 'green',
  OVERDUE: 'red',
  VOID: 'gray',
  COMPLETED: 'green',
  PARTIALLY_REFUNDED: 'amber',
  REFUNDED: 'violet',
  // tickets
  OPEN: 'sky',
  ASSIGNED: 'violet',
  IN_PROGRESS: 'blue',
  RESOLVED: 'green',
  CLOSED: 'gray',
  ESCALATED: 'red',
  // documents
  PENDING: 'amber',
  VERIFIED: 'green',
  REJECTED: 'red',
  // priorities
  LOW: 'gray',
  MEDIUM: 'sky',
  HIGH: 'amber',
  CRITICAL: 'red',
};

export function StatusBadge({ status, className }: { status: string | null | undefined; className?: string }) {
  if (!status) return null;
  return (
    <Badge tone={STATUS_TONES[status] ?? 'gray'} className={className}>
      {humanize(status)}
    </Badge>
  );
}
