import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CalendarPlus, LogIn, Wrench, CheckCircle2 } from 'lucide-react';
import { api } from '@/api';
import type { BedSummary, BookingResponse, Page, RoomResponse } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { Button, LinkButton } from '@/components/ui/Button';
import { DescriptionList } from '@/components/ui/Card';
import { Modal } from '@/components/ui/Modal';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { Skeleton } from '@/components/ui/Skeleton';
import { StatusBadge } from '@/components/ui/Badge';
import { useToast } from '@/components/ui/Toast';
import { TenantName } from '@/features/shared/TenantName';
import { CheckInDialog, ReserveBedDialog } from '@/features/bookings/OccupancyDialogs';
import { useCancelBooking } from '@/features/bookings/api';
import { formatDate, formatDateTime, formatMoney, humanize } from '@/lib/format';
import { isOperator } from '@/lib/roles';
import { useBed, useBedAllocations, useBedStatus } from './api';

export function BedDetailDialog({ room, bed, onClose }: { room: RoomResponse; bed: BedSummary; onClose: () => void }) {
  const user = useUser();
  const toast = useToast();
  const operator = isOperator(user.role);
  const full = useBed(bed.id);
  const allocations = useBedAllocations(bed.status === 'OCCUPIED' ? bed.id : undefined);
  const bookings = useQuery({
    queryKey: ['bookings', 'list', { propertyId: room.propertyId, status: 'RESERVED', size: 100 }],
    enabled: bed.status === 'RESERVED' && operator,
    queryFn: ({ signal }) => api.get<Page<BookingResponse>>('/api/v1/bookings', { propertyId: room.propertyId, status: 'RESERVED', size: 100 }, signal),
  });
  const booking = bookings.data?.content.find((b) => b.bedId === bed.id) ?? null;
  const active = allocations.data?.find((a) => a.status === 'ACTIVE');
  const setStatus = useBedStatus();
  const cancel = useCancelBooking();
  const [dialog, setDialog] = useState<'reserve' | 'checkin' | 'checkin-booking' | 'maintenance' | 'cancel' | null>(null);
  // snapshot so the result view survives the refetch that removes the reservation
  const [bookingSnapshot, setBookingSnapshot] = useState<BookingResponse | null>(null);

  const changeStatus = async (status: 'AVAILABLE' | 'MAINTENANCE') => {
    try {
      await setStatus.mutateAsync({ id: bed.id, status });
      toast.success(status === 'MAINTENANCE' ? 'Bed sent to maintenance' : 'Bed is available again');
      onClose();
    } catch (e) {
      toast.apiError(e, 'Could not change bed status');
    }
  };

  const b = full.data;
  return (
    <>
      <Modal open={dialog === null} onClose={onClose} title={`Room ${room.roomNumber} · Bed ${bed.bedCode}`} description={<StatusBadge status={bed.status} />}>
        <div className="space-y-4">
          <DescriptionList
            items={[
              { label: 'Room type', value: humanize(room.roomType) },
              { label: 'Rent', value: `${formatMoney(b?.monthlyRent ?? room.monthlyRent)}/month` },
              { label: 'Deposit', value: formatMoney(b?.securityDeposit ?? room.securityDeposit) },
              { label: 'Amenities', value: [room.airConditioned && 'AC', room.attachedBathroom && 'Attached bath'].filter(Boolean).join(', ') || 'Standard' },
            ]}
          />

          {bed.status === 'OCCUPIED' && (
            <div className="rounded-xl bg-brand-50 p-3 text-sm dark:bg-brand-950/40">
              {allocations.isLoading ? (
                <Skeleton className="h-5 w-40" />
              ) : active ? (
                <>
                  <p className="font-medium">
                    Occupied by <TenantName id={active.tenantId} />
                  </p>
                  <p className="text-slate-600 dark:text-slate-300">
                    Since {formatDate(active.startDate)} · {formatMoney(active.monthlyRent)}/month
                    {active.expectedEndDate && ` · leaving ${formatDate(active.expectedEndDate)}`}
                  </p>
                </>
              ) : (
                <p>No active stay found.</p>
              )}
            </div>
          )}

          {bed.status === 'RESERVED' && operator && (
            <div className="rounded-xl bg-amber-50 p-3 text-sm dark:bg-amber-950/40">
              {bookings.isLoading ? (
                <Skeleton className="h-5 w-40" />
              ) : booking ? (
                <>
                  <p className="font-medium">
                    {booking.bookingReference} for <TenantName id={booking.tenantId} />
                  </p>
                  <p className="text-slate-600 dark:text-slate-300">
                    Check-in {formatDate(booking.expectedCheckInDate)} · held until {formatDateTime(booking.reservedUntil)}
                  </p>
                </>
              ) : (
                <p>Reservation details unavailable.</p>
              )}
            </div>
          )}

          {operator && (
            <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
              {bed.status === 'AVAILABLE' && room.status === 'ACTIVE' && (
                <>
                  <Button onClick={() => setDialog('checkin')} icon={<LogIn className="h-4 w-4" aria-hidden />}>
                    Walk-in check-in
                  </Button>
                  <Button variant="secondary" onClick={() => setDialog('reserve')} icon={<CalendarPlus className="h-4 w-4" aria-hidden />}>
                    Reserve
                  </Button>
                  <Button variant="outline" onClick={() => setDialog('maintenance')} icon={<Wrench className="h-4 w-4" aria-hidden />}>
                    Maintenance
                  </Button>
                </>
              )}
              {bed.status === 'MAINTENANCE' && (
                <Button loading={setStatus.isPending} onClick={() => void changeStatus('AVAILABLE')} icon={<CheckCircle2 className="h-4 w-4" aria-hidden />}>
                  Mark available
                </Button>
              )}
              {bed.status === 'RESERVED' && booking && (
                <>
                  <Button onClick={() => { setBookingSnapshot(booking); setDialog('checkin-booking'); }} icon={<LogIn className="h-4 w-4" aria-hidden />}>
                    Check in
                  </Button>
                  <Button variant="outline" onClick={() => setDialog('cancel')}>
                    Cancel reservation
                  </Button>
                </>
              )}
              {bed.status === 'OCCUPIED' && active && (
                <LinkButton to={`/tenants/${active.tenantId}`} variant="secondary">
                  Open resident (notice, transfer, check-out)
                </LinkButton>
              )}
            </div>
          )}
          {!operator && <p className="text-xs text-slate-500">Bookings and check-ins are handled by managers.</p>}
        </div>
      </Modal>

      {dialog === 'reserve' && <ReserveBedDialog open onClose={onClose} bed={b ?? null} propertyId={room.propertyId} />}
      {dialog === 'checkin' && <CheckInDialog open onClose={onClose} bed={b ?? null} propertyId={room.propertyId} />}
      {dialog === 'checkin-booking' && bookingSnapshot && <CheckInDialog open onClose={onClose} booking={bookingSnapshot} propertyId={room.propertyId} />}
      <ConfirmDialog
        open={dialog === 'maintenance'}
        title="Send bed to maintenance?"
        description="The bed cannot be reserved or allocated until it is marked available again."
        confirmLabel="Send to maintenance"
        tone="primary"
        loading={setStatus.isPending}
        onClose={() => setDialog(null)}
        onConfirm={() => void changeStatus('MAINTENANCE')}
      />
      <ConfirmDialog
        open={dialog === 'cancel'}
        title="Cancel reservation?"
        description="The bed becomes available again."
        confirmLabel="Cancel reservation"
        reasonLabel="Reason"
        reasonRequired
        loading={cancel.isPending}
        onClose={() => setDialog(null)}
        onConfirm={async (reason) => {
          try {
            await cancel.mutateAsync({ id: booking!.id, reason });
            toast.success('Reservation cancelled');
            onClose();
          } catch (e) {
            toast.apiError(e, 'Could not cancel');
          }
        }}
      />
    </>
  );
}
