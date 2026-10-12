import { Plus } from 'lucide-react';
import { MAINTENANCE_CATEGORIES, MAINTENANCE_STATUSES, PRIORITIES, type MaintenanceResponse } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { LinkButton } from '@/components/ui/Button';
import { Checkbox, Select } from '@/components/ui/Field';
import { PageHeader } from '@/components/ui/PageHeader';
import { FilterPanel } from '@/components/ui/FilterPanel';
import { Pagination } from '@/components/ui/Pagination';
import { ResponsiveTable, type Column } from '@/components/ui/ResponsiveTable';
import { SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { PropertySelect } from '@/features/shared/PropertySelect';
import { usePropertyName, useUserLabel } from '@/features/shared/lookups';
import { formatDateTime, humanize } from '@/lib/format';
import { useSearchState } from '@/lib/useSearchState';
import { useMaintenanceList } from './api';
import { SlaBadge } from './SlaBadge';

function PropertyCell({ id }: { id: number }) {
  return <>{usePropertyName(id)}</>;
}
function AssigneeCell({ t }: { t: MaintenanceResponse }) {
  return <>{useUserLabel(t.assignedToUserId, t.propertyId)}</>;
}

export default function MaintenanceListPage() {
  const user = useUser();
  const isTenant = user.role === 'TENANT';
  const s = useSearchState();
  const filters = {
    page: s.page,
    propertyId: s.getNumber('propertyId'),
    status: s.get('status') || undefined,
    priority: s.get('priority') || undefined,
    category: s.get('category') || undefined,
    slaBreached: s.get('breached') === '1',
    assignedToMe: s.get('mine') === '1',
  };
  const q = useMaintenanceList(filters);

  const columns: Column<MaintenanceResponse>[] = [
    {
      key: 'title',
      header: 'Request',
      primary: true,
      cell: (t) => (
        <span className="block min-w-0">
          <span className="block truncate">{t.title}</span>
          <span className="block text-xs font-normal text-slate-500 dark:text-slate-400">
            {t.ticketNumber} · {humanize(t.category)}
          </span>
        </span>
      ),
    },
    { key: 'priority', header: 'Priority', cell: (t) => <StatusBadge status={t.priority} /> },
    { key: 'status', header: 'Status', cell: (t) => <StatusBadge status={t.status} />, hideOnMobile: true },
    { key: 'sla', header: 'SLA', cell: (t) => <SlaBadge dueAt={t.slaDueAt} breached={t.slaBreached} status={t.status} /> },
    ...(isTenant
      ? []
      : [
          { key: 'property', header: 'Property', cell: (t: MaintenanceResponse) => <PropertyCell id={t.propertyId} /> },
          { key: 'assignee', header: 'Assignee', cell: (t: MaintenanceResponse) => <AssigneeCell t={t} /> },
        ]),
    { key: 'created', header: 'Raised', cell: (t) => formatDateTime(t.createdAt), hideOnMobile: !isTenant },
  ];

  return (
    <div>
      <PageHeader
        title={isTenant ? 'My maintenance requests' : 'Maintenance'}
        subtitle={q.data ? `${q.data.totalElements} requests` : undefined}
        actions={
          <LinkButton to="/maintenance/new" icon={<Plus className="h-4 w-4" aria-hidden />}>
            New request
          </LinkButton>
        }
      />

      <FilterPanel className="grid-cols-2 gap-3 md:grid-cols-4 lg:grid-cols-6">
        {!isTenant && <PropertySelect value={filters.propertyId ?? ''} onChange={(e) => s.set({ propertyId: e.target.value })} containerClassName="col-span-2 md:col-span-1" />}
        <Select label="Status" value={filters.status ?? ''} onChange={(e) => s.set({ status: e.target.value })}>
          <option value="">Any status</option>
          {MAINTENANCE_STATUSES.map((v) => (
            <option key={v} value={v}>
              {humanize(v)}
            </option>
          ))}
        </Select>
        <Select label="Priority" value={filters.priority ?? ''} onChange={(e) => s.set({ priority: e.target.value })}>
          <option value="">Any priority</option>
          {PRIORITIES.map((v) => (
            <option key={v} value={v}>
              {humanize(v)}
            </option>
          ))}
        </Select>
        <Select label="Category" value={filters.category ?? ''} onChange={(e) => s.set({ category: e.target.value })}>
          <option value="">Any category</option>
          {MAINTENANCE_CATEGORIES.map((v) => (
            <option key={v} value={v}>
              {humanize(v)}
            </option>
          ))}
        </Select>
        {!isTenant && (
          <div className="col-span-2 flex flex-wrap items-end gap-x-4 md:col-span-1 lg:col-span-2">
            <Checkbox label="Assigned to me" checked={filters.assignedToMe} onChange={(e) => s.set({ mine: e.target.checked ? '1' : '' })} />
            <Checkbox label="SLA breached" checked={filters.slaBreached} onChange={(e) => s.set({ breached: e.target.checked ? '1' : '' })} />
          </div>
        )}
      </FilterPanel>

      {q.isLoading ? (
        <SkeletonList />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : q.data && q.data.content.length === 0 ? (
        <EmptyState title="No maintenance requests" description="Nothing matches these filters." />
      ) : q.data ? (
        <>
          <ResponsiveTable rows={q.data.content} columns={columns} rowKey={(t) => t.id} href={(t) => `/maintenance/${t.id}`} mobileBadge={(t) => <StatusBadge status={t.status} />} caption="Maintenance requests" />
          <Pagination page={q.data.page} size={q.data.size} totalPages={q.data.totalPages} totalElements={q.data.totalElements} onPage={(p) => s.set({ page: p })} />
        </>
      ) : null}
    </div>
  );
}
