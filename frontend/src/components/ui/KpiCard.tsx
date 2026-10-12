import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { cn } from '@/lib/cn';

export function KpiCard({ label, value, hint, icon, tone = 'default', to }: { label: string; value: ReactNode; hint?: ReactNode; icon?: ReactNode; tone?: 'default' | 'warn' | 'bad' | 'good'; to?: string }) {
  const content = (
    <>
      <div className="flex items-start justify-between gap-2">
        <p className="line-clamp-2 min-h-[2rem] text-[11px] font-medium uppercase leading-4 tracking-wide text-slate-500 dark:text-slate-400 md:text-xs">{label}</p>
        {icon && (
          <span
            className={cn(
              'shrink-0 rounded-lg p-1.5',
              tone === 'default' && 'bg-brand-50 text-brand-600 dark:bg-brand-950 dark:text-brand-300',
              tone === 'good' && 'bg-emerald-50 text-emerald-600 dark:bg-emerald-950 dark:text-emerald-300',
              tone === 'warn' && 'bg-amber-50 text-amber-600 dark:bg-amber-950 dark:text-amber-300',
              tone === 'bad' && 'bg-red-50 text-red-600 dark:bg-red-950 dark:text-red-300',
            )}
            aria-hidden
          >
            {icon}
          </span>
        )}
      </div>
      <p className="mt-1 truncate text-xl font-semibold tabular-nums text-slate-900 dark:text-slate-50 md:text-2xl">{value}</p>
      {hint && <p className="mt-0.5 truncate text-xs text-slate-500 dark:text-slate-400">{hint}</p>}
    </>
  );
  const cls = 'block min-w-0 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900';
  return to ? (
    <Link to={to} className={cn(cls, 'transition hover:border-brand-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 dark:hover:border-brand-700')}>
      {content}
    </Link>
  ) : (
    <div className={cls}>{content}</div>
  );
}
