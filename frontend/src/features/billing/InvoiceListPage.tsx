import { Plus } from 'lucide-react';
import { INVOICE_STATUSES, type InvoiceResponse } from '@/api/types';
import { LinkButton } from '@/components/ui/Button';
import { Input, Select } from '@/components/ui/Field';
import { PageHeader } from '@/components/ui/PageHeader';
import { FilterPanel } from '@/components/ui/FilterPanel';
import { Pagination } from '@/components/ui/Pagination';
import { ResponsiveTable, type Column } from '@/components/ui/ResponsiveTable';
import { SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { PropertySelect } from '@/features/shared/PropertySelect';
import { TenantName } from '@/features/shared/TenantName';
import { formatDate, formatMoney, formatMonth, humanize } from '@/lib/format';
import { useSearchState } from '@/lib/useSearchState';
import { useInvoices } from './api';

export default function InvoiceListPage() {
  const s = useSearchState();
  const month = s.get('month');
  const f = {
    page: s.page,
    propertyId: s.getNumber('propertyId'),
    tenantId: s.getNumber('tenantId'),
    status: s.get('status') || undefined,
    type: s.get('type') || undefined,
    billingMonth: month ? `${month}-01` : undefined,
    dueBefore: s.get('dueBefore') || undefined,
  };
  const q = useInvoices(f);

  const columns: Column<InvoiceResponse>[] = [
    {
      key: 'number',
      header: 'Invoice',
      primary: true,
      cell: (i) => (
        <span className="block min-w-0">
          <span className="block truncate">{i.invoiceNumber}</span>
          <span className="block text-xs font-normal text-slate-500">
            {humanize(i.type)}
            {i.billingMonth ? ` · ${formatMonth(i.billingMonth)}` : ''}
          </span>
        </span>
      ),
    },
    { key: 'tenant', header: 'Resident', cell: (i) => <TenantName id={i.tenantId} /> },
    { key: 'status', header: 'Status', hideOnMobile: true, cell: (i) => <StatusBadge status={i.status} /> },
    { key: 'due', header: 'Due', cell: (i) => formatDate(i.dueDate) },
    { key: 'total', header: 'Total', numeric: true, hideOnMobile: true, cell: (i) => formatMoney(i.totalAmount) },
    { key: 'balance', header: 'Balance', numeric: true, cell: (i) => <span className={i.balance > 0 ? 'font-medium text-slate-900 dark:text-white' : ''}>{formatMoney(i.balance)}</span> },
  ];

  return (
    <div>
      <PageHeader
        title="Invoices"
        subtitle={q.data ? `${q.data.totalElements} invoices` : undefined}
        actions={
          <LinkButton to="/payments/new" icon={<Plus className="h-4 w-4" aria-hidden />}>
            Record payment
          </LinkButton>
        }
      />
      {f.tenantId && (
        <p className="mb-3 text-sm text-slate-600 dark:text-slate-300">
          Showing invoices of <TenantName id={f.tenantId} />.{' '}
          <button type="button" className="font-medium text-brand-700 underline dark:text-brand-300" onClick={() => s.set({ tenantId: '' })}>
            Clear
          </button>
        </p>
      )}
      <FilterPanel className="grid-cols-2 gap-3 md:grid-cols-5">
        <PropertySelect containerClassName="col-span-2 md:col-span-1" value={f.propertyId ?? ''} onChange={(e) => s.set({ propertyId: e.target.value })} />
        <Select label="Status" value={f.status ?? ''} onChange={(e) => s.set({ status: e.target.value })}>
          <option value="">Any status</option>
          {INVOICE_STATUSES.map((v) => (
            <option key={v} value={v}>
              {humanize(v)}
            </option>
          ))}
        </Select>
        <Select label="Type" value={f.type ?? ''} onChange={(e) => s.set({ type: e.target.value })}>
          <option value="">Rent & deposit</option>
          <option value="RENT">Rent</option>
          <option value="SECURITY_DEPOSIT">Security deposit</option>
        </Select>
        <Input label="Billing month" type="month" value={month} onChange={(e) => s.set({ month: e.target.value })} />
        <Input label="Due before" type="date" value={f.dueBefore ?? ''} onChange={(e) => s.set({ dueBefore: e.target.value })} />
      </FilterPanel>
      {q.isLoading ? (
        <SkeletonList />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : q.data && q.data.content.length === 0 ? (
        <EmptyState title="No invoices" description="Nothing matches these filters." />
      ) : q.data ? (
        <>
          <ResponsiveTable rows={q.data.content} columns={columns} rowKey={(i) => i.id} href={(i) => `/invoices/${i.id}`} mobileBadge={(i) => <StatusBadge status={i.status} />} caption="Invoices" />
          <Pagination page={q.data.page} size={q.data.size} totalPages={q.data.totalPages} totalElements={q.data.totalElements} onPage={(p) => s.set({ page: p })} />
        </>
      ) : null}
    </div>
  );
}
