import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { CalendarPlus, IndianRupee, KeyRound, LogIn, Pencil, Trash2 } from 'lucide-react';
import type { AllocationResponse, BookingResponse } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { Button, LinkButton } from '@/components/ui/Button';
import { Card, CardBody, CardHeader, DescriptionList } from '@/components/ui/Card';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { Input } from '@/components/ui/Field';
import { KpiCard } from '@/components/ui/KpiCard';
import { Modal } from '@/components/ui/Modal';
import { PageHeader } from '@/components/ui/PageHeader';
import { Skeleton, SkeletonCards, SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { Tabs } from '@/components/ui/Tabs';
import { useToast } from '@/components/ui/Toast';
import { CheckInDialog, CheckOutDialog, ReserveBedDialog } from '@/features/bookings/OccupancyDialogs';
import { useBookings, useCancelBooking, useTenantAllocations } from '@/features/bookings/api';
import { BedLabel } from './StaySection';
import { usePropertyName, useTenant } from '@/features/shared/lookups';
import { formatDate, formatDateTime, formatMoney, humanize } from '@/lib/format';
import { isOperator } from '@/lib/roles';
import { PASSWORD_HINT, PASSWORD_RULE } from '@/lib/validation';
import { useCreatePortalAccount, useDeleteTenant, useTenantBalance } from './api';
import { DocumentsSection } from './DocumentsSection';
import { EmergencyContactsEditor } from './EmergencyContactsEditor';
import { CurrentStayCard, StayHistory } from './StaySection';

export default function TenantDetailPage() {
  const id = Number(useParams().id);
  const user = useUser();
  const navigate = useNavigate();
  const toast = useToast();
  const operator = isOperator(user.role);
  const tenant = useTenant(id);
  const allocations = useTenantAllocations(id);
  const balance = useTenantBalance(id, operator);
  const propertyName = usePropertyName(tenant.data?.propertyId);
  const portal = useCreatePortalAccount(id);
  const del = useDeleteTenant();
  const [tab, setTab] = useState('overview');
  const reservations = useBookings({ tenantId: id, status: 'RESERVED' });
  const cancelBooking = useCancelBooking();
  // snapshot: the reservation disappears from the query once checked in, but the dialog must stay to show the invoices
  const [checkInBooking, setCheckInBooking] = useState<BookingResponse | null>(null);
  const [checkOutFor, setCheckOutFor] = useState<AllocationResponse | null>(null);
  const [dialog, setDialog] = useState<'portal' | 'checkin' | 'cancel-booking' | 'reserve' | 'delete' | null>(null);
  const [password, setPassword] = useState('');

  if (tenant.isLoading) return <SkeletonCards />;
  if (tenant.error || !tenant.data) return <ErrorState error={tenant.error} onRetry={() => void tenant.refetch()} />;
  const t = tenant.data;
  const active = allocations.data?.find((a) => a.status === 'ACTIVE');
  const reservation = reservations.data?.content[0] ?? null;
  const canCheckIn = operator && !active && !reservation && (t.status === 'PROSPECTIVE' || t.status === 'MOVED_OUT');
  const summary = { id: t.id, propertyId: t.propertyId, fullName: t.fullName, phone: t.phone, email: t.email, status: t.status };

  return (
    <div className="space-y-4">
      <PageHeader
        back={{ to: '/tenants', label: 'Tenants' }}
        title={t.fullName}
        subtitle={
          <span className="flex flex-wrap items-center gap-1.5">
            <StatusBadge status={t.status} /> {propertyName} · {t.phone}
          </span>
        }
        actions={
          operator ? (
            <>
              <LinkButton to={`/tenants/${id}/edit`} variant="outline" icon={<Pencil className="h-4 w-4" aria-hidden />}>
                Edit
              </LinkButton>
              {!t.userId && t.email && (
                <Button variant="outline" onClick={() => setDialog('portal')} icon={<KeyRound className="h-4 w-4" aria-hidden />}>
                  Portal account
                </Button>
              )}
              {t.status === 'PROSPECTIVE' && (
                <Button variant="ghost" onClick={() => setDialog('delete')} icon={<Trash2 className="h-4 w-4 text-red-600" aria-hidden />} aria-label="Delete tenant" />
              )}
            </>
          ) : undefined
        }
      />

      {operator && (
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          {balance.isLoading ? (
            <SkeletonCards count={2} className="col-span-2" />
          ) : balance.data ? (
            <>
              <KpiCard label="Outstanding" value={formatMoney(balance.data.outstanding)} tone={balance.data.outstanding > 0 ? 'bad' : 'good'} icon={<IndianRupee className="h-4 w-4" />} />
              <KpiCard label="Deposit held" value={formatMoney(balance.data.depositHeld)} />
            </>
          ) : null}
          <KpiCard label="Joined" value={formatDate(t.joiningDate)} />
          <KpiCard label="Portal login" value={t.userId ? 'Enabled' : 'None'} hint={t.userId ? undefined : t.email ? 'Can be created' : 'Needs an email'} />
        </div>
      )}

      <Card>
        <CardHeader title="Current stay" />
        <CardBody>
          {allocations.isLoading ? (
            <Skeleton className="h-24" />
          ) : active ? (
            <CurrentStayCard allocation={active} canManage={operator} onCheckOut={setCheckOutFor} />
          ) : reservation ? (
            <div className="space-y-3">
              <div className="rounded-xl bg-amber-50 p-3 text-sm dark:bg-amber-950/40">
                <p className="font-medium text-slate-900 dark:text-slate-100">
                  Reserved {reservation.bookingReference}: <BedLabel bedId={reservation.bedId} />
                </p>
                <p className="text-slate-600 dark:text-slate-300">
                  Expected check-in {formatDate(reservation.expectedCheckInDate)} · held until {formatDateTime(reservation.reservedUntil)}
                </p>
              </div>
              {operator && (
                <div className="flex flex-col gap-2 sm:flex-row">
                  <Button onClick={() => setCheckInBooking(reservation)} icon={<LogIn className="h-4 w-4" aria-hidden />}>
                    Check in now
                  </Button>
                  <Button variant="outline" onClick={() => setDialog('cancel-booking')}>
                    Cancel reservation
                  </Button>
                </div>
              )}
            </div>
          ) : (
            <div className="space-y-3">
              <p className="text-sm text-slate-500">Not currently staying in a bed.</p>
              {canCheckIn && (
                <div className="flex flex-col gap-2 sm:flex-row">
                  <Button onClick={() => setDialog('checkin')} icon={<LogIn className="h-4 w-4" aria-hidden />}>
                    Check in (walk-in)
                  </Button>
                  <Button variant="secondary" onClick={() => setDialog('reserve')} icon={<CalendarPlus className="h-4 w-4" aria-hidden />}>
                    Reserve a bed
                  </Button>
                </div>
              )}
            </div>
          )}
        </CardBody>
      </Card>

      <Card>
        <div className="px-4 pt-2">
          <Tabs
            value={tab}
            onChange={setTab}
            tabs={[
              { id: 'overview', label: 'Profile' },
              { id: 'stays', label: 'Stay history', count: allocations.data?.length },
              ...(operator ? [{ id: 'billing', label: 'Dues', count: balance.data?.openInvoices.length }] : []),
              ...(operator ? [{ id: 'documents', label: 'Documents' }] : []),
              { id: 'contacts', label: 'Emergency contacts', count: t.emergencyContacts?.length ?? 0 },
            ]}
          />
        </div>
        <CardBody>
          {tab === 'overview' && (
            <DescriptionList
              items={[
                { label: 'Email', value: t.email },
                { label: 'Phone', value: t.phone },
                { label: 'Gender', value: humanize(t.gender) },
                { label: 'Date of birth', value: formatDate(t.dateOfBirth) },
                { label: 'Occupation', value: t.occupation },
                { label: 'Company / college', value: t.organizationName },
                { label: 'Joined', value: formatDate(t.joiningDate) },
                { label: 'Moved out', value: formatDate(t.moveOutDate) },
                { label: 'Permanent address', value: t.permanentAddress, full: true },
              ]}
            />
          )}
          {tab === 'stays' &&
            (allocations.isLoading ? (
              <SkeletonList rows={2} />
            ) : allocations.error ? (
              <ErrorState error={allocations.error} onRetry={() => void allocations.refetch()} />
            ) : !allocations.data?.length ? (
              <EmptyState title="No stays yet" />
            ) : (
              <StayHistory allocations={allocations.data} />
            ))}
          {tab === 'billing' &&
            (balance.isLoading ? (
              <SkeletonList rows={2} />
            ) : balance.error ? (
              <ErrorState error={balance.error} onRetry={() => void balance.refetch()} />
            ) : !balance.data?.openInvoices.length ? (
              <EmptyState title="No open invoices" description="All dues are cleared." action={<Link className="text-sm font-medium text-brand-700 hover:underline dark:text-brand-300" to={`/invoices?tenantId=${id}`}>See all invoices</Link>} />
            ) : (
              <div className="space-y-3">
                <ul className="divide-y divide-slate-100 rounded-xl border border-slate-200 dark:divide-slate-800 dark:border-slate-700">
                  {balance.data.openInvoices.map((i) => (
                    <li key={i.id} className="flex flex-wrap items-center justify-between gap-2 p-3">
                      <Link to={`/invoices/${i.id}`} className="min-w-0">
                        <span className="block font-medium text-brand-700 hover:underline dark:text-brand-300">{i.invoiceNumber}</span>
                        <span className="block text-xs text-slate-500">
                          {humanize(i.type)} · due {formatDate(i.dueDate)}
                        </span>
                      </Link>
                      <span className="flex items-center gap-2">
                        <StatusBadge status={i.status} />
                        <span className="font-medium tabular-nums">{formatMoney(i.balance)}</span>
                        <LinkButton size="sm" to={`/payments/new?invoiceId=${i.id}`}>
                          Collect
                        </LinkButton>
                      </span>
                    </li>
                  ))}
                </ul>
                <Link className="text-sm font-medium text-brand-700 hover:underline dark:text-brand-300" to={`/invoices?tenantId=${id}`}>
                  See all invoices
                </Link>
              </div>
            ))}
          {tab === 'documents' && <DocumentsSection tenantId={id} canManage={operator} />}
          {tab === 'contacts' &&
            (operator ? (
              <EmergencyContactsEditor tenantId={id} contacts={t.emergencyContacts ?? []} />
            ) : (
              <DescriptionList items={(t.emergencyContacts ?? []).map((c) => ({ label: `${c.name} (${c.relationship})${c.primary ? ' · primary' : ''}`, value: c.phone }))} />
            ))}
        </CardBody>
      </Card>

      <Modal
        open={dialog === 'portal'}
        onClose={() => setDialog(null)}
        title="Create portal account"
        description={`${t.fullName} will sign in with ${t.email} and this initial password.`}
        size="sm"
        footer={
          <>
            <Button variant="outline" onClick={() => setDialog(null)}>
              Cancel
            </Button>
            <Button
              disabled={!PASSWORD_RULE.test(password)}
              loading={portal.isPending}
              onClick={async () => {
                try {
                  await portal.mutateAsync(password);
                  toast.success('Portal account created', 'Share the initial password with the resident securely.');
                  setPassword('');
                  setDialog(null);
                } catch (e) {
                  toast.apiError(e, 'Could not create account');
                }
              }}
            >
              Create account
            </Button>
          </>
        }
      >
        <Input label="Initial password" type="password" autoComplete="new-password" required value={password} onChange={(e) => setPassword(e.target.value)} hint={PASSWORD_HINT} data-autofocus />
      </Modal>
      {dialog === 'checkin' && <CheckInDialog open onClose={() => setDialog(null)} propertyId={t.propertyId} tenant={summary} />}
      {checkOutFor && <CheckOutDialog open onClose={() => setCheckOutFor(null)} allocation={checkOutFor} />}
      {checkInBooking && <CheckInDialog open onClose={() => setCheckInBooking(null)} propertyId={checkInBooking.propertyId} booking={checkInBooking} />}
      <ConfirmDialog
        open={dialog === 'cancel-booking'}
        title="Cancel reservation?"
        description="The bed is released immediately."
        confirmLabel="Cancel reservation"
        reasonLabel="Reason"
        reasonRequired
        loading={cancelBooking.isPending}
        onClose={() => setDialog(null)}
        onConfirm={async (reason) => {
          try {
            await cancelBooking.mutateAsync({ id: reservation!.id, reason });
            toast.success('Reservation cancelled');
            setDialog(null);
          } catch (e) {
            toast.apiError(e, 'Could not cancel');
          }
        }}
      />
      {dialog === 'reserve' && <ReserveBedDialog open onClose={() => setDialog(null)} propertyId={t.propertyId} tenant={summary} />}
      <ConfirmDialog
        open={dialog === 'delete'}
        title="Delete tenant?"
        description="Only prospective residents without stays can be deleted."
        confirmLabel="Delete"
        loading={del.isPending}
        onClose={() => setDialog(null)}
        onConfirm={async () => {
          try {
            await del.mutateAsync(id);
            toast.success('Tenant deleted');
            navigate('/tenants', { replace: true });
          } catch (e) {
            toast.apiError(e, 'Could not delete');
          }
        }}
      />
    </div>
  );
}
