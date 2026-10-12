import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Ban, BadgePercent, PlusCircle, Wallet } from 'lucide-react';
import { LINE_ITEM_TYPES, type LineItemType } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { Button, LinkButton } from '@/components/ui/Button';
import { Card, CardBody, CardHeader, DescriptionList } from '@/components/ui/Card';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { Input, Select } from '@/components/ui/Field';
import { Modal } from '@/components/ui/Modal';
import { PageHeader } from '@/components/ui/PageHeader';
import { Skeleton } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { useToast } from '@/components/ui/Toast';
import { TenantName } from '@/features/shared/TenantName';
import { usePropertyName } from '@/features/shared/lookups';
import { formatDate, formatDateTime, formatMoney, formatMonth, humanize, methodLabel } from '@/lib/format';
import { isAdmin, isOperator } from '@/lib/roles';
import { useInvoice, useInvoiceActions, usePayments } from './api';
import { ReceiptButton } from './ReceiptButton';

const CHARGE_TYPES = LINE_ITEM_TYPES.filter((t) => t !== 'DISCOUNT' && t !== 'RENT' && t !== 'SECURITY_DEPOSIT');

export default function InvoiceDetailPage() {
  const id = Number(useParams().id);
  const user = useUser();
  const toast = useToast();
  const q = useInvoice(id);
  const payments = usePayments({ invoiceId: id, size: 50 });
  const actions = useInvoiceActions(id);
  const property = usePropertyName(q.data?.propertyId);
  const [dialog, setDialog] = useState<'charge' | 'discount' | 'void' | null>(null);
  const [chargeType, setChargeType] = useState<LineItemType>('ELECTRICITY');
  const [desc, setDesc] = useState('');
  const [amount, setAmount] = useState('');

  if (q.isLoading) return <Skeleton className="h-96" />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const inv = q.data;
  const open = inv.status !== 'VOID' && inv.status !== 'PAID';
  const operator = isOperator(user.role);
  const amountValid = Number(amount) >= 0.01;

  const resetForm = () => {
    setDesc('');
    setAmount('');
    setDialog(null);
  };

  return (
    <div className="space-y-4">
      <PageHeader
        back={{ to: '/invoices', label: 'Invoices' }}
        title={inv.invoiceNumber}
        subtitle={
          <span className="flex flex-wrap items-center gap-1.5">
            <StatusBadge status={inv.status} /> {humanize(inv.type)}
            {inv.billingMonth ? ` · ${formatMonth(inv.billingMonth)}` : ''}
          </span>
        }
        actions={
          operator && open ? (
            <>
              <LinkButton to={`/payments/new?invoiceId=${id}`} icon={<Wallet className="h-4 w-4" aria-hidden />}>
                Record payment
              </LinkButton>
              <Button variant="outline" onClick={() => setDialog('charge')} icon={<PlusCircle className="h-4 w-4" aria-hidden />}>
                Add charge
              </Button>
              <Button variant="outline" onClick={() => setDialog('discount')} icon={<BadgePercent className="h-4 w-4" aria-hidden />}>
                Discount
              </Button>
              {isAdmin(user.role) && inv.amountPaid === 0 && (
                <Button variant="ghost" onClick={() => setDialog('void')} icon={<Ban className="h-4 w-4 text-red-600" aria-hidden />}>
                  Void
                </Button>
              )}
            </>
          ) : undefined
        }
      />

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader title="Line items" />
          <CardBody className="p-0">
            <table className="w-full text-sm">
              <caption className="sr-only">Invoice line items</caption>
              <thead className="bg-slate-50 text-left text-xs uppercase text-slate-500 dark:bg-slate-800/50 dark:text-slate-400">
                <tr>
                  <th scope="col" className="px-4 py-2">Item</th>
                  <th scope="col" className="px-4 py-2 text-right">Amount</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                {(inv.lineItems ?? []).map((li) => (
                  <tr key={li.id}>
                    <td className="px-4 py-2.5">
                      <span className="block text-slate-900 dark:text-slate-100">{li.description}</span>
                      <span className="text-xs text-slate-500">{humanize(li.type)}</span>
                    </td>
                    <td className={'px-4 py-2.5 text-right tabular-nums ' + (li.type === 'DISCOUNT' ? 'text-emerald-700 dark:text-emerald-400' : '')}>
                      {li.type === 'DISCOUNT' ? `− ${formatMoney(Math.abs(li.amount))}` : formatMoney(li.amount)}
                    </td>
                  </tr>
                ))}
              </tbody>
              <tfoot className="border-t-2 border-slate-200 text-sm dark:border-slate-700">
                <tr>
                  <td className="px-4 py-1.5 text-slate-500">Subtotal</td>
                  <td className="px-4 py-1.5 text-right tabular-nums">{formatMoney(inv.subtotal)}</td>
                </tr>
                <tr>
                  <td className="px-4 py-1.5 text-slate-500">Discounts</td>
                  <td className="px-4 py-1.5 text-right tabular-nums">− {formatMoney(inv.discountTotal)}</td>
                </tr>
                <tr className="font-semibold">
                  <td className="px-4 py-1.5">Total</td>
                  <td className="px-4 py-1.5 text-right tabular-nums">{formatMoney(inv.totalAmount)}</td>
                </tr>
                <tr>
                  <td className="px-4 py-1.5 text-slate-500">Paid</td>
                  <td className="px-4 py-1.5 text-right tabular-nums">{formatMoney(inv.amountPaid)}</td>
                </tr>
                <tr className="text-base font-semibold">
                  <td className="px-4 py-2">Balance due</td>
                  <td className="px-4 py-2 text-right tabular-nums">{formatMoney(inv.balance)}</td>
                </tr>
              </tfoot>
            </table>
          </CardBody>
        </Card>
        <Card>
          <CardHeader title="Details" />
          <CardBody>
            <DescriptionList
              className="sm:grid-cols-1"
              items={[
                { label: 'Resident', value: <TenantName id={inv.tenantId} /> },
                { label: 'Property', value: property },
                { label: 'Period', value: inv.periodStart ? `${formatDate(inv.periodStart)} – ${formatDate(inv.periodEnd)}` : '—' },
                { label: 'Issued', value: formatDate(inv.issueDate) },
                { label: 'Due', value: formatDate(inv.dueDate) },
              ]}
            />
          </CardBody>
        </Card>
      </div>

      <Card>
        <CardHeader title="Payments" />
        <CardBody className="p-0">
          {payments.isLoading ? (
            <div className="p-4">
              <Skeleton className="h-12" />
            </div>
          ) : !payments.data?.content.length ? (
            <div className="p-4">
              <EmptyState title="No payments yet" />
            </div>
          ) : (
            <ul className="divide-y divide-slate-100 dark:divide-slate-800">
              {payments.data.content.map((p) => (
                <li key={p.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
                  <span className="min-w-0">
                    <span className="block font-medium">{p.receiptNumber}</span>
                    <span className="block text-xs text-slate-500">
                      {methodLabel(p.method)} · {formatDateTime(p.paidAt)}
                    </span>
                  </span>
                  <span className="flex items-center gap-2">
                    <StatusBadge status={p.status} />
                    <span className="font-medium tabular-nums">{formatMoney(p.amount)}</span>
                    <ReceiptButton paymentId={p.id} receiptNumber={p.receiptNumber} />
                  </span>
                </li>
              ))}
            </ul>
          )}
        </CardBody>
      </Card>

      <Modal
        open={dialog === 'charge' || dialog === 'discount'}
        onClose={resetForm}
        dismissible={!actions.charge.isPending && !actions.discount.isPending}
        size="sm"
        title={dialog === 'charge' ? 'Add charge' : 'Apply discount'}
        footer={
          <>
            <Button variant="outline" onClick={resetForm}>
              Cancel
            </Button>
            <Button
              disabled={!amountValid || !desc.trim()}
              loading={actions.charge.isPending || actions.discount.isPending}
              onClick={async () => {
                try {
                  if (dialog === 'charge') await actions.charge.mutateAsync({ type: chargeType, description: desc.trim(), amount: Number(amount) });
                  else await actions.discount.mutateAsync({ reason: desc.trim(), amount: Number(amount) });
                  toast.success(dialog === 'charge' ? 'Charge added' : 'Discount applied');
                  resetForm();
                } catch (e) {
                  toast.apiError(e, 'Could not update invoice');
                }
              }}
            >
              {dialog === 'charge' ? 'Add charge' : 'Apply discount'}
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          {dialog === 'charge' && (
            <Select label="Charge type" value={chargeType} onChange={(e) => setChargeType(e.target.value as LineItemType)}>
              {CHARGE_TYPES.map((t) => (
                <option key={t} value={t}>
                  {humanize(t)}
                </option>
              ))}
            </Select>
          )}
          <Input label={dialog === 'charge' ? 'Description' : 'Reason'} required maxLength={255} value={desc} onChange={(e) => setDesc(e.target.value)} data-autofocus />
          <Input label="Amount (₹)" type="number" inputMode="decimal" step="0.01" min={0.01} required value={amount} onChange={(e) => setAmount(e.target.value)} />
        </div>
      </Modal>

      <ConfirmDialog
        open={dialog === 'void'}
        title={`Void ${inv.invoiceNumber}?`}
        description="A void invoice no longer counts towards dues or revenue. This cannot be undone."
        confirmLabel="Void invoice"
        reasonLabel="Reason"
        reasonRequired
        loading={actions.void.isPending}
        onClose={() => setDialog(null)}
        onConfirm={async (reason) => {
          try {
            await actions.void.mutateAsync(reason);
            toast.success('Invoice voided');
            setDialog(null);
          } catch (e) {
            toast.apiError(e, 'Could not void invoice');
          }
        }}
      />
    </div>
  );
}
