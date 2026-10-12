import { useId, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface TabItem {
  id: string;
  label: ReactNode;
  count?: number;
}

export function Tabs({ tabs, value, onChange, className }: { tabs: TabItem[]; value: string; onChange: (id: string) => void; className?: string }) {
  const base = useId();
  return (
    <div role="tablist" className={cn('-mx-4 flex gap-1 overflow-x-auto border-b border-slate-200 px-4 scrollbar-none dark:border-slate-800 md:mx-0 md:px-0', className)}>
      {tabs.map((t) => {
        const selected = t.id === value;
        return (
          <button
            key={t.id}
            id={`${base}-${t.id}`}
            role="tab"
            type="button"
            aria-selected={selected}
            onClick={() => onChange(t.id)}
            className={cn(
              'min-h-touch shrink-0 whitespace-nowrap border-b-2 px-3 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-500',
              selected
                ? 'border-brand-600 text-brand-700 dark:border-brand-400 dark:text-brand-300'
                : 'border-transparent text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200',
            )}
          >
            {t.label}
            {t.count !== undefined && <span className="ml-1.5 rounded-full bg-slate-100 px-1.5 text-xs dark:bg-slate-800">{t.count}</span>}
          </button>
        );
      })}
    </div>
  );
}
