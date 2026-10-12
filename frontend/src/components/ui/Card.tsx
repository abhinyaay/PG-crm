import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export function Card({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        // min-w-0: cards are often grid items; without it truncated content would widen the grid track
        'min-w-0 rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900',
        className,
      )}
      {...rest}
    />
  );
}

export function CardHeader({ title, subtitle, actions, className }: { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode; className?: string }) {
  return (
    <div className={cn('flex flex-wrap items-start justify-between gap-2 border-b border-slate-100 px-4 py-3 dark:border-slate-800', className)}>
      <div className="min-w-0">
        <h2 className="text-base font-semibold text-slate-900 dark:text-slate-100">{title}</h2>
        {subtitle && <p className="text-sm text-slate-500 dark:text-slate-400">{subtitle}</p>}
      </div>
      {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

export function CardBody({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  // allow callers to opt out of the default padding with `p-0` (no tailwind-merge dependency)
  const flush = /(^|\s)p-0(\s|$)/.test(className ?? '');
  return <div className={cn(!flush && 'p-4', className)} {...rest} />;
}

/** Definition list that stacks on mobile and goes two columns on wider screens. */
export function DescriptionList({ items, className }: { items: { label: ReactNode; value: ReactNode; full?: boolean }[]; className?: string }) {
  return (
    <dl className={cn('grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2', className)}>
      {items.map((it, i) => (
        <div key={i} className={cn('min-w-0', it.full && 'sm:col-span-2')}>
          <dt className="text-xs font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400">{it.label}</dt>
          <dd className="mt-0.5 break-words text-sm text-slate-900 dark:text-slate-100">{it.value ?? '—'}</dd>
        </div>
      ))}
    </dl>
  );
}
