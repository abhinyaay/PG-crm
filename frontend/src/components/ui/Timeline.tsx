import type { ReactNode } from 'react';
import { formatDateTime } from '@/lib/format';

export interface TimelineItem {
  key: string | number;
  title: ReactNode;
  body?: ReactNode;
  at: string;
  meta?: ReactNode;
  tone?: 'default' | 'muted' | 'warn';
}

export function Timeline({ items }: { items: TimelineItem[] }) {
  return (
    <ol className="relative space-y-4 border-l border-slate-200 pl-5 dark:border-slate-700">
      {items.map((it) => (
        <li key={it.key} className="relative">
          <span
            className={
              'absolute -left-[26px] top-1 h-3 w-3 rounded-full ring-4 ring-white dark:ring-slate-900 ' +
              (it.tone === 'warn' ? 'bg-amber-500' : it.tone === 'muted' ? 'bg-slate-400' : 'bg-brand-500')
            }
            aria-hidden
          />
          <div className="text-sm font-medium text-slate-900 dark:text-slate-100">{it.title}</div>
          {it.body && <div className="mt-0.5 whitespace-pre-line text-sm text-slate-600 dark:text-slate-300">{it.body}</div>}
          <div className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
            <time dateTime={it.at}>{formatDateTime(it.at)}</time>
            {it.meta && <> · {it.meta}</>}
          </div>
        </li>
      ))}
    </ol>
  );
}
