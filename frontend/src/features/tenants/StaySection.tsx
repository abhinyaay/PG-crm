import { useState } from 'react';
import { ArrowRightLeft, BellRing, LogOut, Undo2 } from 'lucide-react';
import type { AllocationResponse } from '@/api/types';
import { Button } from '@/components/ui/Button';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { Badge, StatusBadge } from '@/components/ui/Badge';
import { Skeleton } from '@/components/ui/Skeleton';
import { useToast } from '@/components/ui/Toast';
import { NoticeDialog, TransferDialog } from '@/features/bookings/OccupancyDialogs';
import { useNotice } from '@/features/bookings/api';
import { useBed } from '@/features/rooms/api';
import { usePropertyName } from '@/features/shared/lookups';
import { formatDate, formatMoney, humanize } from '@/lib/format';

export function BedLabel({ bedId }: { bedId: number }) {
  const bed = useBed(bedId);
  if (bed.isLoading) return <Skeleton className="inline-block h-4 w-24" />;
  if (!bed.data) return <>Bed #{bedId}</>;
  return (
    <>
      Room {bed.data.roomNumber} · Bed {bed.data.bedCode}
    </>
  );
}

/**
 * `onCheckOut` lets the parent own the check-out dialog: after a successful check-out this card unmounts (there is
 * no active stay any more) but the settlement result must stay on screen.
 */
export function CurrentStayCard({ allocation, canManage, onCheckOut }: { allocation: AllocationResponse; canManage: boolean; onCheckOut: (a: AllocationResponse) => void }) {
  const toast = useToast();
  const property = usePropertyName(allocation.propertyId);
  const { withdraw } = useNotice();
  const [dialog, setDialog] = useState<'notice' | 'transfer' | 'withdraw' | null>(null);
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="font-semibold text-slate-900 dark:text-slate-100">
            <BedLabel bedId={allocation.bedId} />
          </p>
          <p className="text-sm text-slate-500 dark:text-slate-400">{property}</p>
        </div>
        <span className="flex gap-1">
          <StatusBadge status={allocation.status} />
          {allocation.noticeDate && <Badge tone="amber">On notice</Badge>}
        </span>
      </div>
      <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
        <div>
          <dt className="text-xs text-slate-500">Since</dt>
          <dd>{formatDate(allocation.startDate)}</dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500">Rent</dt>
          <dd>{formatMoney(allocation.monthlyRent)}/mo</dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500">Deposit</dt>
          <dd>{formatMoney(allocation.securityDeposit)}</dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500">Expected check-out</dt>
          <dd>{formatDate(allocation.expectedEndDate)}</dd>
        </div>
      </dl>
      {canManage && (
        <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
          {allocation.noticeDate ? (
            <Button variant="outline" onClick={() => setDialog('withdraw')} icon={<Undo2 className="h-4 w-4" aria-hidden />}>
              Withdraw notice
            </Button>
          ) : (
            <Button variant="outline" onClick={() => setDialog('notice')} icon={<BellRing className="h-4 w-4" aria-hidden />}>
              Record notice
            </Button>
          )}
          <Button variant="outline" onClick={() => setDialog('transfer')} icon={<ArrowRightLeft className="h-4 w-4" aria-hidden />}>
            Transfer
          </Button>
          <Button variant="danger" onClick={() => onCheckOut(allocation)} icon={<LogOut className="h-4 w-4" aria-hidden />}>
            Check out
          </Button>
        </div>
      )}
      {dialog === 'notice' && <NoticeDialog open onClose={() => setDialog(null)} allocation={allocation} />}
      {dialog === 'transfer' && <TransferDialog open onClose={() => setDialog(null)} allocation={allocation} />}
      <ConfirmDialog
        open={dialog === 'withdraw'}
        title="Withdraw notice?"
        description="The resident stays on; the expected check-out date is cleared."
        confirmLabel="Withdraw notice"
        tone="primary"
        loading={withdraw.isPending}
        onClose={() => setDialog(null)}
        onConfirm={async () => {
          try {
            await withdraw.mutateAsync(allocation.id);
            toast.success('Notice withdrawn');
            setDialog(null);
          } catch (e) {
            toast.apiError(e, 'Could not withdraw notice');
          }
        }}
      />
    </div>
  );
}

export function StayHistory({ allocations }: { allocations: AllocationResponse[] }) {
  const sorted = [...allocations].sort((a, b) => b.startDate.localeCompare(a.startDate));
  return (
    <ol className="space-y-2">
      {sorted.map((a) => (
        <li key={a.id} className="rounded-xl border border-slate-200 p-3 text-sm dark:border-slate-700">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="font-medium text-slate-900 dark:text-slate-100">
              <BedLabel bedId={a.bedId} />
            </span>
            <StatusBadge status={a.status} />
          </div>
          <p className="mt-1 text-slate-600 dark:text-slate-300">
            {formatDate(a.startDate)} → {a.endDate ? formatDate(a.endDate) : 'present'} · {formatMoney(a.monthlyRent)}/mo
            {a.endReason && ` · ended by ${humanize(a.endReason).toLowerCase()}`}
          </p>
        </li>
      ))}
    </ol>
  );
}
