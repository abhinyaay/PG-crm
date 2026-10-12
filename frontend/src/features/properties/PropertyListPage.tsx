import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Building2, MapPin, Plus } from 'lucide-react';
import type { PropertyResponse } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { LinkButton } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Select } from '@/components/ui/Field';
import { PageHeader } from '@/components/ui/PageHeader';
import { Pagination } from '@/components/ui/Pagination';
import { SearchInput } from '@/components/ui/SearchInput';
import { SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { humanize, formatPercent } from '@/lib/format';
import { isAdmin } from '@/lib/roles';
import { useDebounce } from '@/lib/useDebounce';
import { useSearchState } from '@/lib/useSearchState';
import { useOccupancy, usePropertyList } from './api';

function OccupancyBar({ id }: { id: number }) {
  const { data } = useOccupancy(id);
  if (!data) return <div className="h-2 w-full animate-pulse rounded-full bg-slate-100 dark:bg-slate-800" />;
  return (
    <div>
      <div className="mb-1 flex justify-between text-xs text-slate-500 dark:text-slate-400">
        <span>
          {data.occupiedBeds}/{data.totalBeds} beds
        </span>
        <span>{formatPercent(data.occupancyRate)}</span>
      </div>
      <div className="h-2 w-full overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={data.occupancyRate} aria-label="Occupancy">
        <div className="h-full rounded-full bg-brand-600" style={{ width: `${Math.min(100, data.occupancyRate)}%` }} />
      </div>
    </div>
  );
}

function PropertyCard({ p }: { p: PropertyResponse }) {
  return (
    <Link to={`/properties/${p.id}`} className="block rounded-2xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500">
      <Card className="h-full p-4 transition hover:border-brand-300 dark:hover:border-brand-700">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <p className="truncate font-semibold text-slate-900 dark:text-slate-100">{p.name}</p>
            <p className="text-xs text-slate-500">{p.code} · {humanize(p.type)}</p>
          </div>
          <StatusBadge status={p.status} />
        </div>
        <p className="mt-2 flex items-center gap-1 truncate text-sm text-slate-600 dark:text-slate-300">
          <MapPin className="h-4 w-4 shrink-0" aria-hidden /> {p.address.line1}, {p.address.city}
        </p>
        <div className="mt-3">
          <OccupancyBar id={p.id} />
        </div>
      </Card>
    </Link>
  );
}

export default function PropertyListPage() {
  const user = useUser();
  const s = useSearchState();
  const [q, setQ] = useState(s.get('q'));
  const dq = useDebounce(q);
  const status = s.get('status');
  const list = usePropertyList({ q: dq || undefined, status: status || undefined, page: s.page });

  return (
    <div>
      <PageHeader
        title="Properties"
        subtitle={list.data ? `${list.data.totalElements} properties` : undefined}
        actions={
          isAdmin(user.role) ? (
            <LinkButton to="/properties/new" icon={<Plus className="h-4 w-4" aria-hidden />}>
              Add property
            </LinkButton>
          ) : undefined
        }
      />
      <div className="mb-4 grid gap-3 sm:grid-cols-[1fr_200px]">
        <SearchInput value={q} onChange={(v) => { setQ(v); s.set({ q: v }); }} placeholder="Search name, code or city" label="Search properties" />
        <Select aria-label="Status" value={status} onChange={(e) => s.set({ status: e.target.value })}>
          <option value="">All statuses</option>
          <option value="ACTIVE">Active</option>
          <option value="INACTIVE">Inactive</option>
          <option value="UNDER_RENOVATION">Under renovation</option>
        </Select>
      </div>
      {list.isLoading ? (
        <SkeletonList />
      ) : list.error ? (
        <ErrorState error={list.error} onRetry={() => void list.refetch()} />
      ) : list.data && list.data.content.length === 0 ? (
        <EmptyState icon={<Building2 className="h-6 w-6" aria-hidden />} title="No properties found" />
      ) : list.data ? (
        <>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {list.data.content.map((p) => (
              <PropertyCard key={p.id} p={p} />
            ))}
          </div>
          <Pagination page={list.data.page} size={list.data.size} totalPages={list.data.totalPages} totalElements={list.data.totalElements} onPage={(p) => s.set({ page: p })} />
        </>
      ) : null}
    </div>
  );
}
