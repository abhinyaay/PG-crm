import { useId, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Check } from 'lucide-react';
import { api } from '@/api';
import type { Page, TenantSummary } from '@/api/types';
import { SearchInput } from '@/components/ui/SearchInput';
import { StatusBadge } from '@/components/ui/Badge';
import { useDebounce } from '@/lib/useDebounce';
import { cn } from '@/lib/cn';

/** Search-as-you-type tenant chooser (radio list, keyboard and screen-reader friendly). */
export function TenantPicker({ propertyId, statuses, value, onChange, error }: { propertyId?: number; statuses?: string[]; value: TenantSummary | null; onChange: (t: TenantSummary | null) => void; error?: string }) {
  const [q, setQ] = useState('');
  const dq = useDebounce(q, 250);
  const name = useId();
  const query = useQuery({
    queryKey: ['tenants', 'picker', propertyId, dq],
    queryFn: ({ signal }) => api.get<Page<TenantSummary>>('/api/v1/tenants', { propertyId, q: dq || undefined, size: 20, sort: 'fullName,asc' }, signal),
    placeholderData: (p) => p,
  });
  const rows = (query.data?.content ?? []).filter((t) => !statuses || statuses.includes(t.status));
  return (
    <fieldset className="space-y-2">
      <legend className="text-sm font-medium text-slate-700 dark:text-slate-300">
        Resident<span className="text-red-600">*</span>
      </legend>
      <SearchInput value={q} onChange={setQ} placeholder="Search by name, phone or email" label="Search residents" />
      <div className="max-h-56 overflow-y-auto rounded-xl border border-slate-200 dark:border-slate-700" role="radiogroup" aria-invalid={!!error}>
        {query.isLoading ? (
          <p className="p-3 text-sm text-slate-500">Searching…</p>
        ) : rows.length === 0 ? (
          <p className="p-3 text-sm text-slate-500">No matching residents. Register them on the Tenants page first.</p>
        ) : (
          rows.map((t) => {
            const selected = value?.id === t.id;
            return (
              <label key={t.id} className={cn('flex min-h-touch cursor-pointer items-center gap-3 border-b border-slate-100 px-3 py-2 last:border-0 dark:border-slate-800', selected && 'bg-brand-50 dark:bg-brand-950/50')}>
                <input type="radio" name={name} className="sr-only" checked={selected} onChange={() => onChange(t)} />
                <span className={cn('flex h-5 w-5 shrink-0 items-center justify-center rounded-full border', selected ? 'border-brand-600 bg-brand-600 text-white' : 'border-slate-300 dark:border-slate-600')} aria-hidden>
                  {selected && <Check className="h-3 w-3" />}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-medium text-slate-900 dark:text-slate-100">{t.fullName}</span>
                  <span className="block truncate text-xs text-slate-500">{t.phone}{t.email ? ` · ${t.email}` : ''}</span>
                </span>
                <StatusBadge status={t.status} />
              </label>
            );
          })
        )}
      </div>
      {error && <p className="text-sm text-red-600" role="alert">{error}</p>}
    </fieldset>
  );
}
