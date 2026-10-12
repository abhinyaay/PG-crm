import { useState } from 'react';
import { Plus, Users } from 'lucide-react';
import type { TenantSummary } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { LinkButton } from '@/components/ui/Button';
import { Input, Select } from '@/components/ui/Field';
import { PageHeader } from '@/components/ui/PageHeader';
import { FilterPanel } from '@/components/ui/FilterPanel';
import { Pagination } from '@/components/ui/Pagination';
import { ResponsiveTable, type Column } from '@/components/ui/ResponsiveTable';
import { SearchInput } from '@/components/ui/SearchInput';
import { SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { PropertySelect } from '@/features/shared/PropertySelect';
import { usePropertyName } from '@/features/shared/lookups';
import { formatDate } from '@/lib/format';
import { isOperator } from '@/lib/roles';
import { useDebounce } from '@/lib/useDebounce';
import { useSearchState } from '@/lib/useSearchState';
import { useTenantList } from './api';

function PropertyCell({ id }: { id: number }) {
  return <>{usePropertyName(id)}</>;
}

export default function TenantListPage() {
  const user = useUser();
  const s = useSearchState();
  const [q, setQ] = useState(s.get('q'));
  const dq = useDebounce(q, 300);
  const f = {
    page: s.page,
    q: dq || undefined,
    propertyId: s.getNumber('propertyId'),
    status: s.get('status') || undefined,
    joinedFrom: s.get('joinedFrom') || undefined,
    joinedTo: s.get('joinedTo') || undefined,
    sort: s.get('sort') || undefined,
  };
  const list = useTenantList(f);

  const columns: Column<TenantSummary>[] = [
    {
      key: 'name',
      header: 'Name',
      primary: true,
      cell: (t) => (
        <span className="block min-w-0">
          <span className="block truncate">{t.fullName}</span>
          <span className="block truncate text-xs font-normal text-slate-500">{t.phone}</span>
        </span>
      ),
    },
    { key: 'email', header: 'Email', hideOnMobile: true, cell: (t) => t.email ?? '—' },
    { key: 'property', header: 'Property', cell: (t) => <PropertyCell id={t.propertyId} /> },
    { key: 'status', header: 'Status', hideOnMobile: true, cell: (t) => <StatusBadge status={t.status} /> },
    { key: 'joined', header: 'Joined', cell: (t) => formatDate(t.joiningDate) },
    { key: 'left', header: 'Moved out', hideOnMobile: true, cell: (t) => formatDate(t.moveOutDate) },
  ];

  return (
    <div>
      <PageHeader
        title="Tenants"
        subtitle={list.data ? `${list.data.totalElements} residents` : undefined}
        actions={
          isOperator(user.role) ? (
            <LinkButton to="/tenants/new" icon={<Plus className="h-4 w-4" aria-hidden />}>
              Add tenant
            </LinkButton>
          ) : undefined
        }
      />
      <div className="mb-4 space-y-3">
        <SearchInput value={q} onChange={(v) => { setQ(v); s.set({ q: v }); }} placeholder="Search name, phone or email" label="Search tenants" />
        <FilterPanel className="grid-cols-2 gap-3 md:grid-cols-5">
          <PropertySelect containerClassName="col-span-2 md:col-span-1" value={f.propertyId ?? ''} onChange={(e) => s.set({ propertyId: e.target.value })} />
          <Select label="Status" value={f.status ?? ''} onChange={(e) => s.set({ status: e.target.value })}>
            <option value="">Any status</option>
            <option value="PROSPECTIVE">Prospective</option>
            <option value="ACTIVE">Active</option>
            <option value="ON_NOTICE">On notice</option>
            <option value="MOVED_OUT">Moved out</option>
          </Select>
          <Select label="Sort" value={f.sort ?? ''} onChange={(e) => s.set({ sort: e.target.value })}>
            <option value="">Name (A–Z)</option>
            <option value="joiningDate,desc">Newest joiners</option>
            <option value="joiningDate,asc">Oldest joiners</option>
            <option value="moveOutDate,desc">Recent move-outs</option>
          </Select>
          <Input label="Joined from" type="date" value={f.joinedFrom ?? ''} onChange={(e) => s.set({ joinedFrom: e.target.value })} />
          <Input label="Joined to" type="date" value={f.joinedTo ?? ''} onChange={(e) => s.set({ joinedTo: e.target.value })} />
        </FilterPanel>
      </div>
      {list.isLoading ? (
        <SkeletonList />
      ) : list.error ? (
        <ErrorState error={list.error} onRetry={() => void list.refetch()} />
      ) : list.data && list.data.content.length === 0 ? (
        <EmptyState icon={<Users className="h-6 w-6" aria-hidden />} title="No tenants found" description="Try a different search or filter." />
      ) : list.data ? (
        <>
          <ResponsiveTable rows={list.data.content} columns={columns} rowKey={(t) => t.id} href={(t) => `/tenants/${t.id}`} mobileBadge={(t) => <StatusBadge status={t.status} />} caption="Tenants" />
          <Pagination page={list.data.page} size={list.data.size} totalPages={list.data.totalPages} totalElements={list.data.totalElements} onPage={(p) => s.set({ page: p })} />
        </>
      ) : null}
    </div>
  );
}
