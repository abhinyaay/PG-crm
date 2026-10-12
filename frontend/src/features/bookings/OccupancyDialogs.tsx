import { useState } from 'react';
import { Link } from 'react-router-dom';
import { CheckCircle2, Plus, Trash2 } from 'lucide-react';
import type { AllocationResponse, BedResponse, BookingResponse, CheckInResponse, CheckOutResponse, TenantSummary } from '@/api/types';
import { ApiError } from '@/api/errors';
import { Button } from '@/components/ui/Button';
import { Checkbox, Input, Textarea } from '@/components/ui/Field';
import { Modal } from '@/components/ui/Modal';
import { StatusBadge } from '@/components/ui/Badge';
import { useToast } from '@/components/ui/Toast';
import { TenantPicker } from '@/features/tenants/TenantPicker';
import { TenantName } from '@/features/shared/TenantName';
import { formatDate, formatDateTime, formatMoney, humanize, todayInBusinessTz } from '@/lib/format';
import { useCheckIn, useCheckOut, useNotice, useReserveBed, useTransfer } from './api';
import { BedSelect } from './BedSelect';

function fieldError(e: unknown, field: string): string | undefined {
  return e instanceof ApiError ? e.fieldErrors.find((f) => f.field === field)?.message : undefined;
}

// ---------------------------------------------------------------- reserve
export function ReserveBedDialog({ open, onClose, bed, propertyId, tenant: fixedTenant }: { open: boolean; onClose: () => void; bed?: BedResponse | null; propertyId: number; tenant?: TenantSummary | null }) {
  const toast = useToast();
  const reserve = useReserveBed();
  const [tenant, setTenant] = useState<TenantSummary | null>(fixedTenant ?? null);
  const [bedId, setBedId] = useState('');
  const [date, setDate] = useState(todayInBusinessTz());
  const [hold, setHold] = useState('48');
  const [notes, setNotes] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const chosenBed = bed?.id ?? (bedId ? Number(bedId) : undefined);

  const submit = async () => {
    setSubmitted(true);
    if (!tenant || !chosenBed || !date) return;
    try {
      const b = await reserve.mutateAsync({ tenantId: tenant.id, bedId: chosenBed, expectedCheckInDate: date, holdHours: Number(hold) || undefined, notes: notes || undefined });
      toast.success('Bed reserved', `${b.bookingReference} — held until ${formatDateTime(b.reservedUntil)}`);
      onClose();
    } catch (e) {
      toast.apiError(e, 'Could not reserve bed');
    }
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      dismissible={!reserve.isPending}
      size="lg"
      title="Reserve a bed"
      description={bed ? `Room ${bed.roomNumber} · Bed ${bed.bedCode} · ${formatMoney(bed.monthlyRent)}/month` : undefined}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button loading={reserve.isPending} onClick={() => void submit()}>
            Reserve
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {fixedTenant ? (
          <p className="rounded-xl bg-slate-50 p-3 text-sm dark:bg-slate-800/50">Resident: {fixedTenant.fullName}</p>
        ) : (
          <TenantPicker propertyId={propertyId} statuses={['PROSPECTIVE', 'MOVED_OUT']} value={tenant} onChange={setTenant} error={submitted && !tenant ? 'Choose a resident' : undefined} />
        )}
        {!bed && <BedSelect propertyId={propertyId} value={bedId} onChange={setBedId} error={submitted && !bedId ? 'Choose a bed' : undefined} />}
        <div className="grid grid-cols-2 gap-3">
          <Input label="Expected check-in" type="date" required value={date} min={todayInBusinessTz()} onChange={(e) => setDate(e.target.value)} />
          <Input label="Hold for (hours)" type="number" inputMode="numeric" min={1} max={168} value={hold} onChange={(e) => setHold(e.target.value)} hint="1–168 hours" />
        </div>
        <Textarea label="Notes" rows={2} maxLength={500} value={notes} onChange={(e) => setNotes(e.target.value)} />
      </div>
    </Modal>
  );
}

// ---------------------------------------------------------------- check-in
export function CheckInDialog({ open, onClose, propertyId, booking, bed, tenant: fixedTenant }: { open: boolean; onClose: () => void; propertyId: number; booking?: BookingResponse | null; bed?: BedResponse | null; tenant?: TenantSummary | null }) {
  const toast = useToast();
  const checkIn = useCheckIn();
  const [tenant, setTenant] = useState<TenantSummary | null>(fixedTenant ?? null);
  const [bedId, setBedId] = useState('');
  const [date, setDate] = useState(todayInBusinessTz());
  const [rent, setRent] = useState('');
  const [deposit, setDeposit] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [result, setResult] = useState<CheckInResponse | null>(null);
  const tenantId = booking?.tenantId ?? tenant?.id;
  const chosenBed = booking ? undefined : (bed?.id ?? (bedId ? Number(bedId) : undefined));

  const close = () => {
    setResult(null);
    onClose();
  };

  const submit = async () => {
    setSubmitted(true);
    if (!tenantId || (!booking && !chosenBed)) return;
    try {
      const r = await checkIn.mutateAsync({
        tenantId,
        bookingId: booking?.id,
        bedId: chosenBed,
        checkInDate: date,
        monthlyRent: rent ? Number(rent) : undefined,
        securityDeposit: deposit ? Number(deposit) : undefined,
      });
      setResult(r);
      toast.success('Checked in');
    } catch (e) {
      toast.apiError(e, 'Check-in failed');
    }
  };

  return (
    <Modal
      open={open}
      onClose={close}
      dismissible={!checkIn.isPending}
      size="lg"
      title={result ? 'Check-in complete' : booking ? `Check in ${booking.bookingReference}` : 'Walk-in check-in'}
      footer={
        result ? (
          <Button onClick={close}>Done</Button>
        ) : (
          <>
            <Button variant="outline" onClick={close}>
              Cancel
            </Button>
            <Button loading={checkIn.isPending} onClick={() => void submit()}>
              Check in
            </Button>
          </>
        )
      }
    >
      {result ? (
        <div className="space-y-3 text-sm">
          <p className="flex items-center gap-2 text-emerald-700 dark:text-emerald-300">
            <CheckCircle2 className="h-5 w-5" aria-hidden /> Stay started on {formatDate(result.allocation.startDate)} at {formatMoney(result.allocation.monthlyRent)}/month.
          </p>
          <p className="font-medium text-slate-800 dark:text-slate-100">Invoices issued</p>
          <ul className="divide-y divide-slate-100 rounded-xl border border-slate-200 dark:divide-slate-800 dark:border-slate-700">
            {result.invoices.map((i) => (
              <li key={i.id} className="flex items-center justify-between gap-2 p-3">
                <Link to={`/invoices/${i.id}`} className="font-medium text-brand-700 hover:underline dark:text-brand-300">
                  {i.invoiceNumber}
                </Link>
                <span className="text-slate-500">{humanize(i.type)} · due {formatDate(i.dueDate)}</span>
                <span className="font-medium tabular-nums">{formatMoney(i.totalAmount)}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <div className="space-y-4">
          {booking ? (
            <p className="rounded-xl bg-slate-50 p-3 text-sm dark:bg-slate-800/50">
              Resident <TenantName id={booking.tenantId} link={false} /> · reserved bed #{booking.bedId} · expected {formatDate(booking.expectedCheckInDate)}
            </p>
          ) : fixedTenant ? (
            <p className="rounded-xl bg-slate-50 p-3 text-sm dark:bg-slate-800/50">Resident: {fixedTenant.fullName}</p>
          ) : (
            <TenantPicker propertyId={propertyId} statuses={['PROSPECTIVE', 'MOVED_OUT']} value={tenant} onChange={setTenant} error={submitted && !tenant ? 'Choose a resident' : undefined} />
          )}
          {!booking &&
            (bed ? (
              <p className="text-sm text-slate-600 dark:text-slate-300">
                Bed: Room {bed.roomNumber} · Bed {bed.bedCode} ({formatMoney(bed.monthlyRent)}/month, deposit {formatMoney(bed.securityDeposit)})
              </p>
            ) : (
              <BedSelect propertyId={propertyId} value={bedId} onChange={setBedId} error={submitted && !bedId ? 'Choose a bed' : undefined} />
            ))}
          <div className="grid gap-3 sm:grid-cols-3">
            <Input label="Check-in date" type="date" required value={date} onChange={(e) => setDate(e.target.value)} error={fieldError(checkIn.error, 'checkInDate')} />
            <Input label="Monthly rent (₹)" type="number" inputMode="decimal" min={0} step="0.01" placeholder="Room default" value={rent} onChange={(e) => setRent(e.target.value)} />
            <Input label="Security deposit (₹)" type="number" inputMode="decimal" min={0} step="0.01" placeholder="Room default" value={deposit} onChange={(e) => setDeposit(e.target.value)} />
          </div>
          <p className="text-xs text-slate-500 dark:text-slate-400">A deposit invoice and a prorated first-month rent invoice are issued automatically.</p>
        </div>
      )}
    </Modal>
  );
}

// ---------------------------------------------------------------- notice
export function NoticeDialog({ open, onClose, allocation }: { open: boolean; onClose: () => void; allocation: AllocationResponse }) {
  const toast = useToast();
  const { give } = useNotice();
  const [date, setDate] = useState(allocation.expectedEndDate ?? '');
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Record notice"
      size="sm"
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            loading={give.isPending}
            disabled={!date}
            onClick={async () => {
              try {
                await give.mutateAsync({ id: allocation.id, expectedCheckOutDate: date });
                toast.success('Notice recorded');
                onClose();
              } catch (e) {
                toast.apiError(e, 'Could not record notice');
              }
            }}
          >
            Save notice
          </Button>
        </>
      }
    >
      <Input label="Expected check-out date" type="date" required min={todayInBusinessTz()} value={date} onChange={(e) => setDate(e.target.value)} data-autofocus />
    </Modal>
  );
}

// ---------------------------------------------------------------- transfer
export function TransferDialog({ open, onClose, allocation }: { open: boolean; onClose: () => void; allocation: AllocationResponse }) {
  const toast = useToast();
  const transfer = useTransfer();
  const [bedId, setBedId] = useState('');
  const [date, setDate] = useState(todayInBusinessTz());
  const [rent, setRent] = useState('');
  return (
    <Modal
      open={open}
      onClose={onClose}
      dismissible={!transfer.isPending}
      title="Transfer to another bed"
      description="The current month stays billed on the old bed; the new rent applies from the next cycle."
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            loading={transfer.isPending}
            disabled={!bedId || !date}
            onClick={async () => {
              try {
                const r = await transfer.mutateAsync({ id: allocation.id, targetBedId: Number(bedId), transferDate: date, newMonthlyRent: rent ? Number(rent) : undefined });
                toast.success('Transfer complete', `New stay from ${formatDate(r.current.startDate)} at ${formatMoney(r.current.monthlyRent)}/month`);
                onClose();
              } catch (e) {
                toast.apiError(e, 'Transfer failed');
              }
            }}
          >
            Transfer
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <BedSelect propertyId={allocation.propertyId} value={bedId} onChange={setBedId} label="Target bed" excludeBedId={allocation.bedId} />
        <div className="grid grid-cols-2 gap-3">
          <Input label="Transfer date" type="date" required value={date} onChange={(e) => setDate(e.target.value)} />
          <Input label="New monthly rent (₹)" type="number" inputMode="decimal" min={0} step="0.01" placeholder="Bed default" value={rent} onChange={(e) => setRent(e.target.value)} />
        </div>
      </div>
    </Modal>
  );
}

// ---------------------------------------------------------------- check-out
export function CheckOutDialog({ open, onClose, allocation }: { open: boolean; onClose: () => void; allocation: AllocationResponse }) {
  const toast = useToast();
  const checkOut = useCheckOut();
  const [date, setDate] = useState(allocation.expectedEndDate && allocation.expectedEndDate <= todayInBusinessTz() ? allocation.expectedEndDate : todayInBusinessTz());
  const [items, setItems] = useState<{ description: string; amount: string }[]>([]);
  const [maintenance, setMaintenance] = useState(false);
  const [result, setResult] = useState<CheckOutResponse | null>(null);
  const [confirming, setConfirming] = useState(false);
  const validItems = items.filter((i) => i.description.trim() && Number(i.amount) > 0);
  const invalid = items.some((i) => (i.description.trim() || i.amount) && !(i.description.trim() && Number(i.amount) > 0));

  const close = () => {
    setResult(null);
    setConfirming(false);
    onClose();
  };

  const submit = async () => {
    try {
      const r = await checkOut.mutateAsync({
        id: allocation.id,
        checkOutDate: date,
        deductions: validItems.map((i) => ({ description: i.description.trim(), amount: Number(i.amount) })),
        sendBedToMaintenance: maintenance,
      });
      setResult(r);
      toast.success('Checked out');
    } catch (e) {
      toast.apiError(e, 'Check-out failed');
      setConfirming(false);
    }
  };

  const s = result?.settlement;
  return (
    <Modal
      open={open}
      onClose={close}
      dismissible={!checkOut.isPending}
      size="lg"
      title={result ? 'Deposit settlement' : 'Check out'}
      description={result ? `Stay ended ${formatDate(result.allocation.endDate)}` : `Deposit held: ${formatMoney(allocation.securityDeposit)}`}
      footer={
        result ? (
          <Button onClick={close}>Done</Button>
        ) : confirming ? (
          <>
            <Button variant="outline" onClick={() => setConfirming(false)} disabled={checkOut.isPending}>
              Back
            </Button>
            <Button variant="danger" loading={checkOut.isPending} onClick={() => void submit()}>
              Confirm check-out
            </Button>
          </>
        ) : (
          <>
            <Button variant="outline" onClick={close}>
              Cancel
            </Button>
            <Button variant="danger" disabled={!date || invalid} onClick={() => setConfirming(true)}>
              Review & check out
            </Button>
          </>
        )
      }
    >
      {s ? (
        <div className="space-y-3">
          <dl className="divide-y divide-slate-100 rounded-xl border border-slate-200 text-sm dark:divide-slate-800 dark:border-slate-700">
            {[
              ['Deposit held', formatMoney(s.depositHeld)],
              ['Outstanding dues', `− ${formatMoney(s.outstandingDues)}`],
              ['Deductions', `− ${formatMoney(s.deductions)}`],
            ].map(([k, v]) => (
              <div key={k} className="flex justify-between p-3">
                <dt className="text-slate-600 dark:text-slate-300">{k}</dt>
                <dd className="tabular-nums">{v}</dd>
              </div>
            ))}
            <div className="flex justify-between bg-emerald-50 p-3 font-semibold dark:bg-emerald-950/40">
              <dt>Refund due to resident</dt>
              <dd className="tabular-nums">{formatMoney(s.refundDue)}</dd>
            </div>
            <div className="flex justify-between bg-red-50 p-3 font-semibold dark:bg-red-950/40">
              <dt>Payable by resident</dt>
              <dd className="tabular-nums">{formatMoney(s.amountPayableByTenant)}</dd>
            </div>
          </dl>
          {s.deductionItems.length > 0 && (
            <ul className="text-sm text-slate-600 dark:text-slate-300">
              {s.deductionItems.map((d, i) => (
                <li key={i}>
                  • {d.description}: {formatMoney(d.amount)}
                </li>
              ))}
            </ul>
          )}
          {s.refundDue > 0 && <p className="text-xs text-slate-500">Record the deposit refund from the deposit payment on the Payments page.</p>}
        </div>
      ) : confirming ? (
        <div className="space-y-2 text-sm text-slate-700 dark:text-slate-200">
          <p>
            Check out on <strong>{formatDate(date)}</strong> with {validItems.length} deduction(s) totalling{' '}
            <strong>{formatMoney(validItems.reduce((a, i) => a + Number(i.amount), 0))}</strong>
            {maintenance ? ', sending the bed to maintenance' : ''}?
          </p>
          <p className="text-slate-500">This ends the stay and cannot be undone.</p>
        </div>
      ) : (
        <div className="space-y-4">
          <Input label="Check-out date" type="date" required value={date} onChange={(e) => setDate(e.target.value)} />
          <fieldset className="space-y-2">
            <legend className="text-sm font-medium text-slate-700 dark:text-slate-300">Deductions from deposit</legend>
            {items.map((it, idx) => (
              <div key={idx} className="grid grid-cols-[1fr_7rem_auto] items-end gap-2">
                <Input aria-label={`Deduction ${idx + 1} description`} placeholder="e.g. Broken chair" maxLength={255} value={it.description} onChange={(e) => setItems((xs) => xs.map((x, i) => (i === idx ? { ...x, description: e.target.value } : x)))} />
                <Input aria-label={`Deduction ${idx + 1} amount`} type="number" inputMode="decimal" min={0.01} step="0.01" placeholder="₹" value={it.amount} onChange={(e) => setItems((xs) => xs.map((x, i) => (i === idx ? { ...x, amount: e.target.value } : x)))} />
                <Button variant="ghost" size="icon" aria-label={`Remove deduction ${idx + 1}`} onClick={() => setItems((xs) => xs.filter((_, i) => i !== idx))}>
                  <Trash2 className="h-4 w-4" aria-hidden />
                </Button>
              </div>
            ))}
            {invalid && <p className="text-sm text-red-600">Each deduction needs a description and an amount above 0.</p>}
            <Button variant="outline" size="sm" onClick={() => setItems((xs) => [...xs, { description: '', amount: '' }])} icon={<Plus className="h-4 w-4" aria-hidden />}>
              Add deduction
            </Button>
          </fieldset>
          <Checkbox label="Send the bed to maintenance" description="e.g. for deep cleaning before the next resident" checked={maintenance} onChange={(e) => setMaintenance(e.target.checked)} />
        </div>
      )}
    </Modal>
  );
}

export function AllocationStatusLine({ a }: { a: AllocationResponse }) {
  return (
    <span className="flex flex-wrap items-center gap-1.5">
      <StatusBadge status={a.status} />
      {a.noticeDate && a.status === 'ACTIVE' && <StatusBadge status="ON_NOTICE" />}
    </span>
  );
}
