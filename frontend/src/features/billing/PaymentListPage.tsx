import { Plus } from 'lucide-react';
import { PAYMENT_METHODS, type PaymentResponse } from '@/api/types';
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
import { formatDateTime, formatMoney, methodLabel } from '@/lib/format';
import { useSearchState } from '@/lib/useSearchState';
import { usePayments } from './api';
import { ReceiptButton } from './ReceiptButton';

export default function PaymentListPage() {
  const s = useSearchState();
  const f = {
    page: s.page,
    propertyId: s.getNumber('propertyId'),
    tenantId: s.getNumber('tenantId'),
    method: s.get('method') || undefined,
    from: s.get('from') || undefined,
    to: s.get('to') || undefined,
  };
  const q = usePayments(f);
  const columns: Column<PaymentResponse>[] = [
    {
      key: 'receipt',
      header: 'Receipt',
      primary: true,
      cell: (p) => (
        <span className="block min-w-0">
          <span className="block truncate">{p.receiptNumber}</span>
          <span className="block text-xs font-normal text-slate-500">{formatDateTime(p.paidAt)}</span>
        </span>
      ),
    },
    { key: 'tenant', header: 'Resident', cell: (p) => <TenantName id={p.tenantId} /> },
    { key: 'method', header: 'Method', cell: (p) => methodLabel(p.method) },
    { key: 'status', header: 'Status', hideOnMobile: true, cell: (p) => <StatusBadge status={p.status} /> },
    { key: 'amount', header: 'Amount', numeric: true, cell: (p) => <span className="font-medium">{formatMoney(p.amount)}</span> },
    { key: 'pdf', header: <span className="sr-only">Receipt PDF</span>, hideOnMobile: true, cell: (p) => <ReceiptButton paymentId={p.id} receiptNumber={p.receiptNumber} label="PDF" /> },
  ];
  return (
    <div>
      <PageHeader
        title="Payments"
        subtitle={q.data ? `${q.data.totalElements} payments` : undefined}
        actions={
          <LinkButton to="/payments/new" icon={<Plus className="h-4 w-4" aria-hidden />}>
            Record payment
          </LinkButton>
        }
      />
      <FilterPanel className="grid-cols-2 gap-3 md:grid-cols-4">
        <PropertySelect containerClassName="col-span-2 md:col-span-1" value={f.propertyId ?? ''} onChange={(e) => s.set({ propertyId: e.target.value })} />
        <Select label="Method" containerClassName="col-span-2 md:col-span-1" value={f.method ?? ''} onChange={(e) => s.set({ method: e.target.value })}>
          <option value="">Any method</option>
          {PAYMENT_METHODS.map((m) => (
            <option key={m} value={m}>
              {methodLabel(m)}
            </option>
          ))}
        </Select>
        <Input label="From" type="date" value={f.from ?? ''} onChange={(e) => s.set({ from: e.target.value })} />
        <Input label="To" type="date" value={f.to ?? ''} onChange={(e) => s.set({ to: e.target.value })} />
      </FilterPanel>
      {q.isLoading ? (
        <SkeletonList />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : q.data && q.data.content.length === 0 ? (
        <EmptyState title="No payments" />
      ) : q.data ? (
        <>
          <ResponsiveTable rows={q.data.content} columns={columns} rowKey={(p) => p.id} href={(p) => `/payments/${p.id}`} mobileBadge={(p) => <StatusBadge status={p.status} />} caption="Payments" />
          <Pagination page={q.data.page} size={q.data.size} totalPages={q.data.totalPages} totalElements={q.data.totalElements} onPage={(p) => s.set({ page: p })} />
        </>
      ) : null}
    </div>
  );
}
