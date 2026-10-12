import { useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Undo2 } from 'lucide-react';
import { ApiError } from '@/api/errors';
import { useUser } from '@/auth/AuthProvider';
import { Button } from '@/components/ui/Button';
import { Card, CardBody, CardHeader, DescriptionList } from '@/components/ui/Card';
import { Input, Textarea } from '@/components/ui/Field';
import { Modal } from '@/components/ui/Modal';
import { PageHeader } from '@/components/ui/PageHeader';
import { Skeleton } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { useToast } from '@/components/ui/Toast';
import { TenantName } from '@/features/shared/TenantName';
import { formatDateTime, formatMoney, methodLabel } from '@/lib/format';
import { IdempotentSubmission } from '@/lib/idempotency';
import { isAdmin } from '@/lib/roles';
import { usePayment, useRecordRefund, useRefunds } from './api';
import { ReceiptButton } from './ReceiptButton';

export default function PaymentDetailPage() {
  const id = Number(useParams().id);
  const user = useUser();
  const toast = useToast();
  const q = usePayment(id);
  const refunds = useRefunds(id);
  const refund = useRecordRefund(id);
  const submission = useRef(new IdempotentSubmission<{ amount: number; reason: string }>());
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [uncertain, setUncertain] = useState(false);

  if (q.isLoading) return <Skeleton className="h-80" />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const p = q.data;
  const refundable = Math.round((p.amount - p.refundedAmount) * 100) / 100;
  const valid = Number(amount) >= 0.01 && Number(amount) <= refundable && reason.trim().length > 0;

  const submitRefund = async () => {
    const body = { amount: Math.round(Number(amount) * 100) / 100, reason: reason.trim() };
    const key = submission.current.keyFor(body);
    try {
      const r = await refund.mutateAsync({ body, key });
      submission.current.reset();
      setUncertain(false);
      if (r.replayed) toast.info('Refund already recorded', `${r.data.refundReference} was replayed — no duplicate refund was created.`);
      else toast.success('Refund recorded', `${r.data.refundReference} · ${formatMoney(r.data.amount)}`);
      setOpen(false);
      setAmount('');
      setReason('');
    } catch (e) {
      const ambiguous = e instanceof ApiError && (e.status === 0 || e.status >= 500);
      setUncertain(ambiguous);
      toast.apiError(e, ambiguous ? 'Refund status unknown — retry safely' : 'Refund failed');
    }
  };

  return (
    <div className="space-y-4">
      <PageHeader
        back={{ to: '/payments', label: 'Payments' }}
        title={p.receiptNumber}
        subtitle={
          <span className="flex items-center gap-1.5">
            <StatusBadge status={p.status} /> {formatMoney(p.amount)} via {methodLabel(p.method)}
          </span>
        }
        actions={
          <>
            <ReceiptButton paymentId={p.id} receiptNumber={p.receiptNumber} label="Open receipt" size="md" />
            {isAdmin(user.role) && refundable > 0 && (
              <Button variant="outline" onClick={() => { setAmount(String(refundable)); setOpen(true); }} icon={<Undo2 className="h-4 w-4" aria-hidden />}>
                Refund
              </Button>
            )}
          </>
        }
      />
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader title="Payment" />
          <CardBody>
            <DescriptionList
              items={[
                { label: 'Resident', value: <TenantName id={p.tenantId} /> },
                { label: 'Invoice', value: <Link className="font-medium text-brand-700 hover:underline dark:text-brand-300" to={`/invoices/${p.invoiceId}`}>Invoice #{p.invoiceId}</Link> },
                { label: 'Amount', value: formatMoney(p.amount) },
                { label: 'Refunded', value: formatMoney(p.refundedAmount) },
                { label: 'Paid at', value: formatDateTime(p.paidAt) },
                { label: 'Reference', value: p.externalReference },
                ...(p.notes ? [{ label: 'Notes', value: p.notes, full: true }] : []),
              ]}
            />
          </CardBody>
        </Card>
        <Card>
          <CardHeader title="Refunds" />
          <CardBody className="p-0">
            {refunds.isLoading ? (
              <div className="p-4">
                <Skeleton className="h-12" />
              </div>
            ) : !refunds.data?.length ? (
              <div className="p-4">
                <EmptyState title="No refunds" />
              </div>
            ) : (
              <ul className="divide-y divide-slate-100 dark:divide-slate-800">
                {refunds.data.map((r) => (
                  <li key={r.id} className="flex items-center justify-between gap-2 px-4 py-3 text-sm">
                    <span>
                      <span className="block font-medium">{r.refundReference}</span>
                      <span className="block text-xs text-slate-500">
                        {r.reason} · {formatDateTime(r.refundedAt)}
                      </span>
                    </span>
                    <span className="font-medium tabular-nums">{formatMoney(r.amount)}</span>
                  </li>
                ))}
              </ul>
            )}
          </CardBody>
        </Card>
      </div>
      <Modal
        open={open}
        onClose={() => setOpen(false)}
        dismissible={!refund.isPending}
        title="Record refund"
        description={`Up to ${formatMoney(refundable)} can be refunded.`}
        size="sm"
        footer={
          <>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button variant="danger" disabled={!valid} loading={refund.isPending} onClick={() => void submitRefund()}>
              {uncertain ? 'Retry safely' : 'Refund'}
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <Input label="Amount (₹)" type="number" inputMode="decimal" step="0.01" min={0.01} max={refundable} required value={amount} onChange={(e) => setAmount(e.target.value)} error={amount && Number(amount) > refundable ? `At most ${formatMoney(refundable)}` : undefined} data-autofocus />
          <Textarea label="Reason" required rows={2} maxLength={255} value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
      </Modal>
    </div>
  );
}
