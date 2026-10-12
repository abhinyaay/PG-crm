import { useState } from 'react';
import { CalendarPlus, LogIn } from 'lucide-react';
import type { AllocationResponse, BookingResponse } from '@/api/types';
import { Button } from '@/components/ui/Button';
import { Input, Select } from '@/components/ui/Field';
import { PageHeader } from '@/components/ui/PageHeader';
import { Pagination } from '@/components/ui/Pagination';
import { ResponsiveTable, type Column } from '@/components/ui/ResponsiveTable';
import { SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { Tabs } from '@/components/ui/Tabs';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/components/ui/Toast';
import { PropertySelect } from '@/features/shared/PropertySelect';
import { usePropertyOptions } from '@/features/shared/lookups';
import { TenantName } from '@/features/shared/TenantName';
import { BedLabel } from '@/features/tenants/StaySection';
import { formatDate, formatDateTime, formatMoney } from '@/lib/format';
import { useSearchState } from '@/lib/useSearchState';
import { useAllocations, useBookings, useCancelBooking } from './api';
import { CheckInDialog, ReserveBedDialog } from './OccupancyDialogs';

export default function BookingsPage() {
  const s = useSearchState();
  const toast = useToast();
  const tab = s.get('tab') || 'bookings';
  const propertyId = s.getNumber('propertyId');
  const properties = usePropertyOptions();
  const bookingStatus = s.get('status') || (tab === 'bookings' ? 'RESERVED' : '');
  const bookings = useBookings({ propertyId, status: bookingStatus === 'ALL' ? undefined : bookingStatus, page: s.page });
  const stays = useAllocations({ propertyId, status: 'ACTIVE', expectedEndBefore: s.get('endBefore') || undefined, page: s.page });
  const cancel = useCancelBooking();
  const [reserveOpen, setReserveOpen] = useState(false);
  const [checkInFor, setCheckInFor] = useState<BookingResponse | null>(null);
  const [walkIn, setWalkIn] = useState(false);
  const [cancelFor, setCancelFor] = useState<BookingResponse | null>(null);
  const actionProperty = propertyId ?? (properties.data?.length === 1 ? properties.data[0]!.id : undefined);

  const bookingCols: Column<BookingResponse>[] = [
    { key: 'ref', header: 'Booking', primary: true, cell: (b) => <span>{b.bookingReference}</span> },
    { key: 'tenant', header: 'Resident', cell: (b) => <TenantName id={b.tenantId} /> },
    { key: 'bed', header: 'Bed', cell: (b) => <BedLabel bedId={b.bedId} /> },
    { key: 'checkin', header: 'Check-in', cell: (b) => formatDate(b.expectedCheckInDate) },
    { key: 'until', header: 'Held until', hideOnMobile: true, cell: (b) => (b.status === 'RESERVED' ? formatDateTime(b.reservedUntil) : '—') },
    { key: 'status', header: 'Status', hideOnMobile: true, cell: (b) => <StatusBadge status={b.status} /> },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      cell: (b) =>
        b.status === 'RESERVED' ? (
          <span className="flex gap-2">
            <Button size="sm" onClick={() => setCheckInFor(b)}>
              Check in
            </Button>
            <Button size="sm" variant="outline" onClick={() => setCancelFor(b)}>
              Cancel
            </Button>
          </span>
        ) : null,
    },
  ];

  const stayCols: Column<AllocationResponse>[] = [
    { key: 'tenant', header: 'Resident', primary: true, cell: (a) => <TenantName id={a.tenantId} /> },
    { key: 'bed', header: 'Bed', cell: (a) => <BedLabel bedId={a.bedId} /> },
    { key: 'since', header: 'Since', cell: (a) => formatDate(a.startDate) },
    { key: 'rent', header: 'Rent', numeric: true, cell: (a) => formatMoney(a.monthlyRent) },
    { key: 'end', header: 'Expected check-out', cell: (a) => formatDate(a.expectedEndDate) },
  ];

  return (
    <div>
      <PageHeader
        title="Bookings & stays"
        actions={
          <>
            <Button variant="secondary" onClick={() => setWalkIn(true)} disabled={!actionProperty} icon={<LogIn className="h-4 w-4" aria-hidden />}>
              Walk-in check-in
            </Button>
            <Button onClick={() => setReserveOpen(true)} disabled={!actionProperty} icon={<CalendarPlus className="h-4 w-4" aria-hidden />}>
              Reserve bed
            </Button>
          </>
        }
      />
      <Tabs
        className="mb-4"
        value={tab}
        onChange={(t) => s.set({ tab: t, status: '', endBefore: '' })}
        tabs={[
          { id: 'bookings', label: 'Reservations' },
          { id: 'stays', label: 'Active stays' },
        ]}
      />
      <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4">
        <PropertySelect containerClassName="col-span-2 md:col-span-1" value={propertyId ?? ''} onChange={(e) => s.set({ propertyId: e.target.value })} />
        {tab === 'bookings' ? (
          <Select label="Status" value={bookingStatus} onChange={(e) => s.set({ status: e.target.value })}>
            <option value="RESERVED">Reserved</option>
            <option value="CHECKED_IN">Checked in</option>
            <option value="CANCELLED">Cancelled</option>
            <option value="EXPIRED">Expired</option>
            <option value="ALL">All</option>
          </Select>
        ) : (
          <Input label="Leaving before" type="date" value={s.get('endBefore')} onChange={(e) => s.set({ endBefore: e.target.value })} />
        )}
      </div>

      {!actionProperty && <p className="mb-3 text-sm text-slate-500 dark:text-slate-400">Select a property to reserve a bed or check someone in.</p>}
      {tab === 'bookings' ? (
        bookings.isLoading ? (
          <SkeletonList />
        ) : bookings.error ? (
          <ErrorState error={bookings.error} onRetry={() => void bookings.refetch()} />
        ) : !bookings.data?.content.length ? (
          <EmptyState title="No reservations" description="Reserve a bed from here or from the bed board." />
        ) : (
          <>
            <ResponsiveTable rows={bookings.data.content} columns={bookingCols} rowKey={(b) => b.id} mobileBadge={(b) => <StatusBadge status={b.status} />} caption="Reservations" />
            <Pagination page={bookings.data.page} size={bookings.data.size} totalPages={bookings.data.totalPages} totalElements={bookings.data.totalElements} onPage={(p) => s.set({ page: p })} />
          </>
        )
      ) : stays.isLoading ? (
        <SkeletonList />
      ) : stays.error ? (
        <ErrorState error={stays.error} onRetry={() => void stays.refetch()} />
      ) : !stays.data?.content.length ? (
        <EmptyState title="No active stays match" />
      ) : (
        <>
          <ResponsiveTable rows={stays.data.content} columns={stayCols} rowKey={(a) => a.id} href={(a) => `/tenants/${a.tenantId}`} caption="Active stays" />
          <Pagination page={stays.data.page} size={stays.data.size} totalPages={stays.data.totalPages} totalElements={stays.data.totalElements} onPage={(p) => s.set({ page: p })} />
        </>
      )}

      {reserveOpen && actionProperty && <ReserveBedDialog open onClose={() => setReserveOpen(false)} propertyId={actionProperty} />}
      {walkIn && actionProperty && <CheckInDialog open onClose={() => setWalkIn(false)} propertyId={actionProperty} />}
      {checkInFor && <CheckInDialog open onClose={() => setCheckInFor(null)} propertyId={checkInFor.propertyId} booking={checkInFor} />}
      <ConfirmDialog
        open={!!cancelFor}
        title={`Cancel ${cancelFor?.bookingReference ?? ''}?`}
        description="The bed is released immediately."
        confirmLabel="Cancel reservation"
        reasonLabel="Reason"
        reasonRequired
        loading={cancel.isPending}
        onClose={() => setCancelFor(null)}
        onConfirm={async (reason) => {
          try {
            await cancel.mutateAsync({ id: cancelFor!.id, reason });
            toast.success('Reservation cancelled');
            setCancelFor(null);
          } catch (e) {
            toast.apiError(e, 'Could not cancel');
          }
        }}
      />
    </div>
  );
}
