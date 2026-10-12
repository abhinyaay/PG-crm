import { Plus } from 'lucide-react';
import { COMPLAINT_CATEGORIES, COMPLAINT_STATUSES, PRIORITIES, type ComplaintResponse } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { LinkButton } from '@/components/ui/Button';
import { Checkbox, Select } from '@/components/ui/Field';
import { PageHeader } from '@/components/ui/PageHeader';
import { FilterPanel } from '@/components/ui/FilterPanel';
import { Pagination } from '@/components/ui/Pagination';
import { ResponsiveTable, type Column } from '@/components/ui/ResponsiveTable';
import { SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { Badge, StatusBadge } from '@/components/ui/Badge';
import { PropertySelect } from '@/features/shared/PropertySelect';
import { usePropertyName, useUserLabel } from '@/features/shared/lookups';
import { SlaBadge } from '@/features/maintenance/SlaBadge';
import { formatDateTime, humanize } from '@/lib/format';
import { useSearchState } from '@/lib/useSearchState';
import { useComplaintList } from './api';

function PropertyCell({ id }: { id: number }) {
  return <>{usePropertyName(id)}</>;
}
function AssigneeCell({ c }: { c: ComplaintResponse }) {
  return <>{useUserLabel(c.assignedToUserId, c.propertyId)}</>;
}

export default function ComplaintListPage() {
  const user = useUser();
  const isTenant = user.role === 'TENANT';
  const s = useSearchState();
  const f = {
    page: s.page,
    propertyId: s.getNumber('propertyId'),
    status: s.get('status') || undefined,
    category: s.get('category') || undefined,
    priority: s.get('priority') || undefined,
    minEscalationLevel: s.get('escalated') === '1' ? 1 : undefined,
    assignedToMe: s.get('mine') === '1',
  };
  const q = useComplaintList(f);

  const columns: Column<ComplaintResponse>[] = [
    {
      key: 'subject',
      header: 'Complaint',
      primary: true,
      cell: (c) => (
        <span className="block min-w-0">
          <span className="block truncate">{c.subject}</span>
          <span className="block text-xs font-normal text-slate-500 dark:text-slate-400">
            {c.complaintNumber} · {humanize(c.category)}
          </span>
        </span>
      ),
    },
    { key: 'priority', header: 'Priority', cell: (c) => <StatusBadge status={c.priority} /> },
    { key: 'status', header: 'Status', hideOnMobile: true, cell: (c) => <StatusBadge status={c.status} /> },
    { key: 'level', header: 'Escalation', cell: (c) => (c.escalationLevel > 0 ? <Badge tone="red">Level {c.escalationLevel}</Badge> : <span className="text-slate-400">—</span>) },
    { key: 'due', header: 'Due', cell: (c) => <SlaBadge dueAt={c.dueAt} status={c.status} /> },
    ...(isTenant
      ? []
      : [
          { key: 'property', header: 'Property', cell: (c: ComplaintResponse) => <PropertyCell id={c.propertyId} /> },
          { key: 'assignee', header: 'Assignee', cell: (c: ComplaintResponse) => <AssigneeCell c={c} /> },
        ]),
    { key: 'created', header: 'Raised', hideOnMobile: true, cell: (c) => formatDateTime(c.createdAt) },
  ];

  return (
    <div>
      <PageHeader
        title={isTenant ? 'My complaints' : 'Complaints'}
        subtitle={q.data ? `${q.data.totalElements} complaints` : undefined}
        actions={
          <LinkButton to="/complaints/new" icon={<Plus className="h-4 w-4" aria-hidden />}>
            New complaint
          </LinkButton>
        }
      />
      <FilterPanel className="grid-cols-2 gap-3 md:grid-cols-4 lg:grid-cols-6">
        {!isTenant && <PropertySelect value={f.propertyId ?? ''} onChange={(e) => s.set({ propertyId: e.target.value })} containerClassName="col-span-2 md:col-span-1" />}
        <Select label="Status" value={f.status ?? ''} onChange={(e) => s.set({ status: e.target.value })}>
          <option value="">Any status</option>
          {COMPLAINT_STATUSES.map((v) => (
            <option key={v} value={v}>
              {humanize(v)}
            </option>
          ))}
        </Select>
        <Select label="Category" value={f.category ?? ''} onChange={(e) => s.set({ category: e.target.value })}>
          <option value="">Any category</option>
          {COMPLAINT_CATEGORIES.map((v) => (
            <option key={v} value={v}>
              {humanize(v)}
            </option>
          ))}
        </Select>
        <Select label="Priority" value={f.priority ?? ''} onChange={(e) => s.set({ priority: e.target.value })}>
          <option value="">Any priority</option>
          {PRIORITIES.map((v) => (
            <option key={v} value={v}>
              {humanize(v)}
            </option>
          ))}
        </Select>
        {!isTenant && (
          <div className="col-span-2 flex flex-wrap items-end gap-x-4 md:col-span-1 lg:col-span-2">
            <Checkbox label="Assigned to me" checked={f.assignedToMe} onChange={(e) => s.set({ mine: e.target.checked ? '1' : '' })} />
            <Checkbox label="Escalated" checked={f.minEscalationLevel === 1} onChange={(e) => s.set({ escalated: e.target.checked ? '1' : '' })} />
          </div>
        )}
      </FilterPanel>
      {q.isLoading ? (
        <SkeletonList />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : q.data && q.data.content.length === 0 ? (
        <EmptyState title="No complaints" description="Nothing matches these filters." />
      ) : q.data ? (
        <>
          <ResponsiveTable rows={q.data.content} columns={columns} rowKey={(c) => c.id} href={(c) => `/complaints/${c.id}`} mobileBadge={(c) => <StatusBadge status={c.status} />} caption="Complaints" />
          <Pagination page={q.data.page} size={q.data.size} totalPages={q.data.totalPages} totalElements={q.data.totalElements} onPage={(p) => s.set({ page: p })} />
        </>
      ) : null}
    </div>
  );
}
