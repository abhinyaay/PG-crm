import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ChevronDown } from 'lucide-react';
import { api } from '@/api';
import type { AuditLogResponse, Page } from '@/api/types';
import { Input, Select } from '@/components/ui/Field';
import { PageHeader } from '@/components/ui/PageHeader';
import { FilterPanel } from '@/components/ui/FilterPanel';
import { Pagination } from '@/components/ui/Pagination';
import { SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { Badge } from '@/components/ui/Badge';
import { formatDateTime, humanize } from '@/lib/format';
import { useDebounce } from '@/lib/useDebounce';
import { useSearchState } from '@/lib/useSearchState';

const ENTITY_TYPES = ['USER', 'PROPERTY', 'ROOM', 'BED', 'TENANT', 'BOOKING', 'ALLOCATION', 'INVOICE', 'PAYMENT', 'REFUND', 'MAINTENANCE', 'COMPLAINT', 'ORGANIZATION'];

/** Kolkata calendar date → start/end instant for the audit `from`/`to` filters. */
const dayStart = (d: string) => (d ? new Date(`${d}T00:00:00+05:30`).toISOString() : undefined);
const dayEnd = (d: string) => (d ? new Date(`${d}T23:59:59.999+05:30`).toISOString() : undefined);

export default function AuditLogPage() {
  const s = useSearchState();
  const [action, setAction] = useState(s.get('action'));
  const da = useDebounce(action, 400);
  const [expanded, setExpanded] = useState<number | null>(null);
  const f = {
    page: s.page,
    size: 25,
    entityType: s.get('entityType') || undefined,
    entityId: s.getNumber('entityId'),
    actorUserId: s.getNumber('actor'),
    action: da.trim().toUpperCase() || undefined,
    from: dayStart(s.get('from')),
    to: dayEnd(s.get('to')),
  };
  const q = useQuery({
    queryKey: ['audit', f],
    queryFn: ({ signal }) => api.get<Page<AuditLogResponse>>('/api/v1/audit-logs', { ...f, sort: 'createdAt,desc' }, signal),
    placeholderData: (p) => p,
  });

  return (
    <div>
      <PageHeader title="Audit log" subtitle="Append-only record of every change" />
      <FilterPanel className="grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-6">
        <Input label="Action" placeholder="e.g. PAYMENT_RECORDED" value={action} onChange={(e) => { setAction(e.target.value); s.set({ action: e.target.value }); }} containerClassName="col-span-2" />
        <Select label="Entity" value={s.get('entityType')} onChange={(e) => s.set({ entityType: e.target.value })}>
          <option value="">Any entity</option>
          {ENTITY_TYPES.map((t) => (
            <option key={t} value={t}>
              {humanize(t)}
            </option>
          ))}
        </Select>
        <Input label="Entity id" type="number" inputMode="numeric" value={s.get('entityId')} onChange={(e) => s.set({ entityId: e.target.value })} />
        <Input label="From" type="date" value={s.get('from')} onChange={(e) => s.set({ from: e.target.value })} />
        <Input label="To" type="date" value={s.get('to')} onChange={(e) => s.set({ to: e.target.value })} />
      </FilterPanel>
      {q.isLoading ? (
        <SkeletonList />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : !q.data?.content.length ? (
        <EmptyState title="No audit entries" />
      ) : (
        <>
          <ul className="divide-y divide-slate-100 overflow-hidden rounded-2xl border border-slate-200 bg-white dark:divide-slate-800 dark:border-slate-800 dark:bg-slate-900">
            {q.data.content.map((a) => {
              const open = expanded === a.id;
              const hasDetails = a.details && Object.keys(a.details).length > 0;
              return (
                <li key={a.id}>
                  <button
                    type="button"
                    aria-expanded={open}
                    onClick={() => setExpanded(open ? null : a.id)}
                    className="flex min-h-touch w-full flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3 text-left text-sm hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-500 dark:hover:bg-slate-800/50"
                  >
                    <Badge tone="blue">{a.action}</Badge>
                    <span className="text-slate-700 dark:text-slate-200">
                      {humanize(a.entityType)}
                      {a.entityId ? ` #${a.entityId}` : ''}
                    </span>
                    <span className="text-slate-500">by {a.actorEmail ?? `user #${a.actorUserId ?? '?'}`}</span>
                    <span className="ml-auto flex items-center gap-2 text-xs text-slate-500">
                      {formatDateTime(a.createdAt)}
                      <ChevronDown className={'h-4 w-4 transition ' + (open ? 'rotate-180' : '')} aria-hidden />
                    </span>
                  </button>
                  {open && (
                    <div className="space-y-2 bg-slate-50 px-4 py-3 text-xs dark:bg-slate-800/40">
                      <p className="break-all text-slate-600 dark:text-slate-300">
                        IP {a.ipAddress ?? '—'} · correlation <span className="font-mono">{a.correlationId ?? '—'}</span>
                      </p>
                      {hasDetails ? (
                        <pre className="max-w-full overflow-x-auto whitespace-pre-wrap break-all rounded-lg bg-white p-2 font-mono text-slate-700 dark:bg-slate-900 dark:text-slate-200">{JSON.stringify(a.details, null, 2)}</pre>
                      ) : (
                        <p className="text-slate-500">No extra details.</p>
                      )}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
          <Pagination page={q.data.page} size={q.data.size} totalPages={q.data.totalPages} totalElements={q.data.totalElements} onPage={(p) => s.set({ page: p })} />
        </>
      )}
    </div>
  );
}
