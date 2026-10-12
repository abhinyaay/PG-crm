import { useState } from 'react';
import { ChevronDown } from 'lucide-react';
import type { InvoiceResponse } from '@/api/types';
import { Card, CardBody, CardHeader } from '@/components/ui/Card';
import { PageHeader } from '@/components/ui/PageHeader';
import { Pagination } from '@/components/ui/Pagination';
import { Skeleton, SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { Tabs } from '@/components/ui/Tabs';
import { useInvoice, useInvoices, usePayments } from '@/features/billing/api';
import { ReceiptButton } from '@/features/billing/ReceiptButton';
import { formatDate, formatDateTime, formatMoney, formatMonth, humanize, methodLabel } from '@/lib/format';
import { useSearchState } from '@/lib/useSearchState';

function InvoiceLines({ id }: { id: number }) {
  const q = useInvoice(id);
  if (q.isLoading) return <Skeleton className="h-16" />;
  if (!q.data) return null;
  return (
    <ul className="space-y-1 text-sm">
      {(q.data.lineItems ?? []).map((li) => (
        <li key={li.id} className="flex justify-between gap-2">
          <span className="text-slate-600 dark:text-slate-300">{li.description}</span>
          <span className="tabular-nums">{li.type === 'DISCOUNT' ? `− ${formatMoney(Math.abs(li.amount))}` : formatMoney(li.amount)}</span>
        </li>
      ))}
      <li className="flex justify-between gap-2 border-t border-slate-200 pt-1 font-medium dark:border-slate-700">
        <span>Paid so far</span>
        <span className="tabular-nums">{formatMoney(q.data.amountPaid)}</span>
      </li>
    </ul>
  );
}

function InvoiceRow({ i, defaultOpen }: { i: InvoiceResponse; defaultOpen: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <li className="rounded-2xl border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
      <button type="button" aria-expanded={open} onClick={() => setOpen(!open)} className="flex min-h-touch w-full items-center justify-between gap-3 p-4 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 rounded-2xl">
        <span className="min-w-0">
          <span className="block font-medium text-slate-900 dark:text-slate-100">
            {i.type === 'RENT' && i.billingMonth ? `Rent · ${formatMonth(i.billingMonth)}` : humanize(i.type)}
          </span>
          <span className="block text-xs text-slate-500">
            {i.invoiceNumber} · due {formatDate(i.dueDate)}
          </span>
        </span>
        <span className="flex shrink-0 items-center gap-2">
          <span className="text-right">
            <span className="block font-semibold tabular-nums">{formatMoney(i.balance > 0 ? i.balance : i.totalAmount)}</span>
            <StatusBadge status={i.status} />
          </span>
          <ChevronDown className={'h-4 w-4 text-slate-400 transition ' + (open ? 'rotate-180' : '')} aria-hidden />
        </span>
      </button>
      {open && (
        <div className="border-t border-slate-100 p-4 dark:border-slate-800">
          <InvoiceLines id={i.id} />
        </div>
      )}
    </li>
  );
}

export default function MyBillsPage() {
  const s = useSearchState();
  const tab = s.get('tab') || 'invoices';
  const highlighted = s.getNumber('invoice');
  const invoices = useInvoices({ page: tab === 'invoices' ? s.page : 0, size: 12 }, tab === 'invoices');
  const payments = usePayments({ page: tab === 'payments' ? s.page : 0, size: 12 }, tab === 'payments');
  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader title="My bills" subtitle="Payments are collected at the front desk (cash, UPI, card or bank transfer)." />
      <Tabs
        className="mb-4"
        value={tab}
        onChange={(t) => s.set({ tab: t })}
        tabs={[
          { id: 'invoices', label: 'Invoices' },
          { id: 'payments', label: 'Payments & receipts' },
        ]}
      />
      {tab === 'invoices' ? (
        invoices.isLoading ? (
          <SkeletonList />
        ) : invoices.error ? (
          <ErrorState error={invoices.error} onRetry={() => void invoices.refetch()} />
        ) : !invoices.data?.content.length ? (
          <EmptyState title="No invoices yet" />
        ) : (
          <>
            <ul className="space-y-2">
              {invoices.data.content.map((i) => (
                <InvoiceRow key={i.id} i={i} defaultOpen={i.id === highlighted} />
              ))}
            </ul>
            <Pagination page={invoices.data.page} size={invoices.data.size} totalPages={invoices.data.totalPages} totalElements={invoices.data.totalElements} onPage={(p) => s.set({ page: p })} />
          </>
        )
      ) : payments.isLoading ? (
        <SkeletonList />
      ) : payments.error ? (
        <ErrorState error={payments.error} onRetry={() => void payments.refetch()} />
      ) : !payments.data?.content.length ? (
        <EmptyState title="No payments yet" />
      ) : (
        <Card>
          <CardHeader title="Payments" />
          <CardBody className="p-0">
            <ul className="divide-y divide-slate-100 dark:divide-slate-800">
              {payments.data.content.map((p) => (
                <li key={p.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
                  <span>
                    <span className="block font-medium tabular-nums">{formatMoney(p.amount)}</span>
                    <span className="block text-xs text-slate-500">
                      {p.receiptNumber} · {methodLabel(p.method)} · {formatDateTime(p.paidAt)}
                    </span>
                    {p.refundedAmount > 0 && <span className="text-xs text-violet-700 dark:text-violet-300">Refunded {formatMoney(p.refundedAmount)}</span>}
                  </span>
                  <ReceiptButton paymentId={p.id} receiptNumber={p.receiptNumber} />
                </li>
              ))}
            </ul>
            <div className="px-4 pb-4">
              <Pagination page={payments.data.page} size={payments.data.size} totalPages={payments.data.totalPages} totalElements={payments.data.totalElements} onPage={(p) => s.set({ page: p })} />
            </div>
          </CardBody>
        </Card>
      )}
    </div>
  );
}
