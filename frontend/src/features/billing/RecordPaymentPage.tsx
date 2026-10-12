import { useMemo, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { AlertTriangle, CheckCircle2, History, RotateCcw } from 'lucide-react';
import { PAYMENT_METHODS, type PaymentResponse, type RecordPaymentRequest, type TenantSummary } from '@/api/types';
import { ApiError } from '@/api/errors';
import { Button, LinkButton } from '@/components/ui/Button';
import { Card, CardBody, CardHeader, DescriptionList } from '@/components/ui/Card';
import { Input, Select, Textarea } from '@/components/ui/Field';
import { PageHeader } from '@/components/ui/PageHeader';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { StickyActions } from '@/components/ui/StickyActions';
import { useToast } from '@/components/ui/Toast';
import { TenantPicker } from '@/features/tenants/TenantPicker';
import { useTenantBalance } from '@/features/tenants/api';
import { TenantName } from '@/features/shared/TenantName';
import { formatDate, formatMoney, humanize, isoToKolkataLocal, kolkataLocalToIso, methodLabel } from '@/lib/format';
import { IdempotentSubmission } from '@/lib/idempotency';
import { applyFieldErrors } from '@/api/errors';
import { useInvoice, useRecordPayment } from './api';
import { ReceiptButton } from './ReceiptButton';

/** Failures where the request may or may not have been applied: retrying with the same key is safe and required. */
function isAmbiguous(e: unknown): boolean {
  return e instanceof ApiError && (e.status === 0 || e.status >= 500 || e.status === 429 || e.status === 409);
}

function InvoiceChooser({ onPick }: { onPick: (id: number) => void }) {
  const [tenant, setTenant] = useState<TenantSummary | null>(null);
  const balance = useTenantBalance(tenant?.id);
  return (
    <Card>
      <CardHeader title="Choose an invoice" subtitle="Find the resident, then pick one of their open invoices." />
      <CardBody className="space-y-4">
        <TenantPicker value={tenant} onChange={setTenant} />
        {tenant &&
          (balance.isLoading ? (
            <Skeleton className="h-16" />
          ) : !balance.data?.openInvoices.length ? (
            <p className="text-sm text-slate-500">{tenant.fullName} has no open invoices.</p>
          ) : (
            <ul className="divide-y divide-slate-100 rounded-xl border border-slate-200 dark:divide-slate-800 dark:border-slate-700">
              {balance.data.openInvoices.map((i) => (
                <li key={i.id}>
                  <button type="button" onClick={() => onPick(i.id)} className="flex min-h-touch w-full items-center justify-between gap-2 p-3 text-left hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-500 dark:hover:bg-slate-800/50">
                    <span>
                      <span className="block font-medium">{i.invoiceNumber}</span>
                      <span className="block text-xs text-slate-500">
                        {humanize(i.type)} · due {formatDate(i.dueDate)}
                      </span>
                    </span>
                    <span className="flex items-center gap-2">
                      <StatusBadge status={i.status} />
                      <span className="font-medium tabular-nums">{formatMoney(i.balance)}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          ))}
      </CardBody>
    </Card>
  );
}

export default function RecordPaymentPage() {
  const [params, setParams] = useSearchParams();
  const invoiceId = params.get('invoiceId') ? Number(params.get('invoiceId')) : undefined;
  const invoice = useInvoice(invoiceId);
  const navigate = useNavigate();
  const toast = useToast();
  const record = useRecordPayment();
  /** One logical submission = one Idempotency-Key, reused on retries of the same body. */
  const submission = useRef(new IdempotentSubmission<RecordPaymentRequest>());
  const [pendingKey, setPendingKey] = useState<string | null>(null);
  const [ambiguous, setAmbiguous] = useState<ApiError | null>(null);
  const [result, setResult] = useState<{ payment: PaymentResponse; replayed: boolean } | null>(null);

  const balance = invoice.data?.balance ?? 0;
  const schema = useMemo(
    () =>
      z.object({
        amount: z.coerce
          .number({ invalid_type_error: 'Enter an amount' })
          .min(0.01, 'Amount must be at least ₹0.01')
          .max(balance || Number.MAX_SAFE_INTEGER, `Cannot exceed the balance of ${formatMoney(balance)}`),
        method: z.enum(PAYMENT_METHODS),
        externalReference: z.string().trim().max(100).optional(),
        paidAt: z.string().min(1, 'When was it paid?'),
        notes: z.string().trim().max(500).optional(),
      }),
    [balance],
  );
  type Values = z.infer<typeof schema>;
  const defaults = useMemo<Values>(() => ({ amount: balance, method: 'UPI', externalReference: '', paidAt: isoToKolkataLocal(new Date()), notes: '' }), [balance]);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<Values>({
    resolver: zodResolver(schema),
    values: defaults,
    resetOptions: { keepDirtyValues: true },
  });

  const submit = handleSubmit(async (v) => {
    if (!invoice.data) return;
    const body: RecordPaymentRequest = {
      invoiceId: invoice.data.id,
      amount: Math.round(v.amount * 100) / 100,
      method: v.method,
      externalReference: v.externalReference || undefined,
      paidAt: kolkataLocalToIso(v.paidAt),
      notes: v.notes || undefined,
    };
    const key = submission.current.keyFor(body);
    setPendingKey(key);
    try {
      const r = await record.mutateAsync({ body, key });
      submission.current.reset();
      setAmbiguous(null);
      setResult({ payment: r.data, replayed: r.replayed });
      if (r.replayed) toast.info('Payment already recorded', 'The server returned the original payment for this submission — no duplicate was created.');
      else toast.success('Payment recorded', `${r.data.receiptNumber} · ${formatMoney(r.data.amount)}`);
    } catch (e) {
      if (isAmbiguous(e)) {
        // keep the key: a retry of the same submission must reuse it
        setAmbiguous(e as ApiError);
        toast.apiError(e, 'Payment status unknown');
        return;
      }
      setAmbiguous(null);
      const unmapped = applyFieldErrors(e, setError, ['amount', 'method', 'externalReference', 'paidAt', 'notes']);
      if (!(e instanceof ApiError) || e.fieldErrors.length === 0 || unmapped.length) toast.apiError(e, 'Payment not recorded');
    }
  });

  if (!invoiceId) {
    return (
      <div className="mx-auto max-w-2xl">
        <PageHeader title="Record payment" back={{ to: '/payments', label: 'Payments' }} />
        <InvoiceChooser onPick={(id) => setParams({ invoiceId: String(id) })} />
      </div>
    );
  }

  if (invoice.isLoading) return <Skeleton className="h-96" />;
  if (invoice.error || !invoice.data) return <ErrorState error={invoice.error} onRetry={() => void invoice.refetch()} />;
  const inv = invoice.data;

  if (result) {
    const p = result.payment;
    return (
      <div className="mx-auto max-w-xl space-y-4">
        <PageHeader title={result.replayed ? 'Payment already recorded' : 'Payment recorded'} />
        <Card>
          <CardBody className="space-y-4">
            <div className={'flex items-start gap-3 rounded-xl p-3 text-sm ' + (result.replayed ? 'bg-amber-50 text-amber-900 dark:bg-amber-950/40 dark:text-amber-200' : 'bg-emerald-50 text-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-200')}>
              {result.replayed ? <History className="h-5 w-5 shrink-0" aria-hidden /> : <CheckCircle2 className="h-5 w-5 shrink-0" aria-hidden />}
              <p>
                {result.replayed
                  ? 'This submission had already been processed. The server replayed the original payment (Idempotent-Replayed) — nothing was charged twice.'
                  : 'The payment was applied to the invoice.'}
              </p>
            </div>
            <DescriptionList
              items={[
                { label: 'Receipt', value: p.receiptNumber },
                { label: 'Amount', value: formatMoney(p.amount) },
                { label: 'Method', value: methodLabel(p.method) },
                { label: 'Paid at', value: formatDate(p.paidAt) },
                { label: 'Invoice', value: inv.invoiceNumber },
                { label: 'Reference', value: p.externalReference },
              ]}
            />
            <div className="flex flex-col gap-2 sm:flex-row">
              <ReceiptButton paymentId={p.id} receiptNumber={p.receiptNumber} label="Open PDF receipt" size="md" />
              <LinkButton to={`/invoices/${inv.id}`} variant="outline">
                View invoice
              </LinkButton>
              <LinkButton to={`/payments/${p.id}`} variant="ghost">
                Payment details
              </LinkButton>
            </div>
          </CardBody>
        </Card>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader title="Record payment" back={{ to: `/invoices/${inv.id}`, label: inv.invoiceNumber }} />
      <Card className="mb-4">
        <CardBody>
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <span className="font-semibold">{inv.invoiceNumber}</span>
            <StatusBadge status={inv.status} />
          </div>
          <DescriptionList
            className="grid-cols-2 sm:grid-cols-3"
            items={[
              { label: 'Resident', value: <TenantName id={inv.tenantId} /> },
              { label: 'Type', value: humanize(inv.type) },
              { label: 'Total', value: formatMoney(inv.totalAmount) },
              { label: 'Paid so far', value: formatMoney(inv.amountPaid) },
              { label: 'Balance due', value: <span className="text-base font-semibold">{formatMoney(inv.balance)}</span> },
              { label: 'Due date', value: formatDate(inv.dueDate) },
            ]}
          />
        </CardBody>
      </Card>

      {inv.balance <= 0 || inv.status === 'VOID' ? (
        <ErrorState title="Nothing to collect" error={new Error(inv.status === 'VOID' ? 'This invoice is void.' : 'This invoice is fully paid.')} />
      ) : (
        <form onSubmit={submit} noValidate>
          {ambiguous && (
            <div role="alert" className="mb-4 flex items-start gap-3 rounded-2xl border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-200">
              <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" aria-hidden />
              <div>
                <p className="font-medium">We could not confirm whether the payment was saved.</p>
                <p>
                  Press <strong>Retry safely</strong> without changing the details: the same Idempotency-Key is sent, so the payment can never be recorded twice. Changing the details starts a new
                  payment.
                </p>
                {ambiguous.correlationId && <p className="mt-1 font-mono text-xs">Ref: {ambiguous.correlationId}</p>}
              </div>
            </div>
          )}
          <Card>
            <CardBody className="grid gap-4 sm:grid-cols-2">
              <Input label="Amount (₹)" type="number" inputMode="decimal" step="0.01" min={0.01} max={inv.balance} required error={errors.amount?.message} {...register('amount')} />
              <Select label="Method" required error={errors.method?.message} {...register('method')}>
                {PAYMENT_METHODS.map((m) => (
                  <option key={m} value={m}>
                    {methodLabel(m)}
                  </option>
                ))}
              </Select>
              <Input label="Reference (UTR / cheque no.)" maxLength={100} error={errors.externalReference?.message} {...register('externalReference')} />
              <Input label="Paid at (IST)" type="datetime-local" required error={errors.paidAt?.message} {...register('paidAt')} />
              <Textarea label="Notes" containerClassName="sm:col-span-2" rows={2} maxLength={500} error={errors.notes?.message} {...register('notes')} />
              {pendingKey && (
                <p className="text-xs text-slate-500 sm:col-span-2">
                  Idempotency-Key: <span className="break-all font-mono">{pendingKey}</span>
                </p>
              )}
            </CardBody>
          </Card>
          <StickyActions>
            <Button variant="outline" onClick={() => navigate(-1)} disabled={record.isPending}>
              Cancel
            </Button>
            <Button type="submit" size="lg" loading={record.isPending} icon={ambiguous ? <RotateCcw className="h-4 w-4" aria-hidden /> : undefined}>
              {ambiguous ? 'Retry safely' : `Record payment`}
            </Button>
          </StickyActions>
          <p className="mt-3 text-center text-xs text-slate-500 md:text-right">
            <Link to={`/invoices/${inv.id}`} className="hover:underline">
              Back to invoice
            </Link>
          </p>
        </form>
      )}
    </div>
  );
}
