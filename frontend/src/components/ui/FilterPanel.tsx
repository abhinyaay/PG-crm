import { useId, useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { SlidersHorizontal } from 'lucide-react';
import { cn } from '@/lib/cn';

const IGNORED = new Set(['page', 'tab', 'q', 'sort']);

/**
 * List filters: always visible from `md` up; on phones collapsed behind a "Filters" toggle (showing how many are
 * active) so the results are visible without scrolling past a wall of selects.
 */
export function FilterPanel({ children, className }: { children: ReactNode; className?: string }) {
  const [open, setOpen] = useState(false);
  const [params] = useSearchParams();
  const id = useId();
  const active = [...params.keys()].filter((k) => !IGNORED.has(k) && params.get(k)).length;
  return (
    <div className="mb-4">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen((o) => !o)}
        className="inline-flex min-h-touch items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 text-sm font-medium text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 md:hidden"
      >
        <SlidersHorizontal className="h-4 w-4" aria-hidden />
        Filters
        {active > 0 && <span className="rounded-full bg-brand-600 px-1.5 text-xs text-white">{active}</span>}
      </button>
      <div id={id} className={cn(open ? 'mt-3 grid' : 'hidden', 'md:mt-0 md:grid', className)}>
        {children}
      </div>
    </div>
  );
}
