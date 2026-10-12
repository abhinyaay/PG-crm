import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronRight } from 'lucide-react';
import { cn } from '@/lib/cn';

export interface Column<T> {
  key: string;
  header: ReactNode;
  cell: (row: T) => ReactNode;
  /** Shown as the card title on mobile. Exactly one column should set this. */
  primary?: boolean;
  /** Hide on the mobile card. */
  hideOnMobile?: boolean;
  /** Right-align (money / numbers). */
  numeric?: boolean;
  className?: string;
}

interface Props<T> {
  rows: T[];
  columns: Column<T>[];
  rowKey: (row: T) => string | number;
  /** Row → link target; makes rows/cards navigable. */
  href?: (row: T) => string;
  /** Extra element rendered top-right on mobile cards (usually a status badge). */
  mobileBadge?: (row: T) => ReactNode;
  caption?: string;
  className?: string;
}

/**
 * A table on md+ screens and a stack of cards on phones (no horizontal scrolling of the page).
 */
export function ResponsiveTable<T>({ rows, columns, rowKey, href, mobileBadge, caption, className }: Props<T>) {
  const navigate = useNavigate();
  const primary = columns.find((c) => c.primary) ?? columns[0]!;
  const secondary = columns.filter((c) => c !== primary && !c.hideOnMobile);

  return (
    <div className={className}>
      {/* phones */}
      <ul className="space-y-2 md:hidden">
        {rows.map((row) => {
          const body = (
            <>
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0 font-medium text-slate-900 dark:text-slate-100">{primary.cell(row)}</div>
                <div className="flex shrink-0 items-center gap-1">
                  {mobileBadge?.(row)}
                  {href && <ChevronRight className="h-4 w-4 text-slate-400" aria-hidden />}
                </div>
              </div>
              {secondary.length > 0 && (
                <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1.5 text-sm">
                  {secondary.map((c) => (
                    <div key={c.key} className="min-w-0">
                      <dt className="text-xs text-slate-500 dark:text-slate-400">{c.header}</dt>
                      <dd className="break-words text-slate-800 dark:text-slate-200">{c.cell(row)}</dd>
                    </div>
                  ))}
                </dl>
              )}
            </>
          );
          return (
            <li key={rowKey(row)}>
              {href ? (
                <div
                  role="link"
                  tabIndex={0}
                  onClick={(e) => {
                    if ((e.target as HTMLElement).closest('a,button')) return;
                    navigate(href(row));
                  }}
                  onKeyDown={(e) => e.key === 'Enter' && navigate(href(row))}
                  className="block cursor-pointer rounded-2xl border border-slate-200 bg-white p-4 shadow-sm transition active:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 dark:border-slate-800 dark:bg-slate-900 dark:active:bg-slate-800"
                >
                  {body}
                </div>
              ) : (
                <div className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900">{body}</div>
              )}
            </li>
          );
        })}
      </ul>

      {/* tablets / desktop */}
      <div className="hidden overflow-x-auto rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900 md:block">
        <table className="min-w-full divide-y divide-slate-200 text-sm dark:divide-slate-800">
          {caption && <caption className="sr-only">{caption}</caption>}
          <thead className="bg-slate-50 dark:bg-slate-800/50">
            <tr>
              {columns.map((c) => (
                <th
                  key={c.key}
                  scope="col"
                  className={cn('whitespace-nowrap px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400', c.numeric && 'text-right', c.className)}
                >
                  {c.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
            {rows.map((row) => (
              <tr
                key={rowKey(row)}
                className={cn(href && 'cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/50')}
                onClick={(e) => {
                  if (!href || (e.target as HTMLElement).closest('a,button,input,select')) return;
                  navigate(href(row));
                }}
              >
                {columns.map((c) => (
                  <td key={c.key} className={cn('px-4 py-3 align-top text-slate-700 dark:text-slate-300', c.numeric && 'text-right tabular-nums', c.className)}>
                    {c.cell(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
