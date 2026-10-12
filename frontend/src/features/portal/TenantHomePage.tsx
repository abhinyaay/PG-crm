import { Link } from 'react-router-dom';
import { BedDouble, CalendarClock, IndianRupee, MessageSquareWarning, ShieldCheck, Wrench } from 'lucide-react';
import type { TenantDashboard } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { LinkButton } from '@/components/ui/Button';
import { Card, CardBody, CardHeader } from '@/components/ui/Card';
import { KpiCard } from '@/components/ui/KpiCard';
import { PageHeader } from '@/components/ui/PageHeader';
import { SkeletonCards } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { useDashboard } from '@/features/dashboard/api';
import { ReceiptButton } from '@/features/billing/ReceiptButton';
import { formatDate, formatDateTime, formatMoney, methodLabel } from '@/lib/format';

export default function TenantHomePage() {
  const user = useUser();
  const q = useDashboard<TenantDashboard>();
  const d = q.data;
  return (
    <div className="space-y-4">
      <PageHeader title={`Hello, ${user.fullName.split(' ')[0]}`} subtitle={d?.propertyName ?? 'Your stay at a glance'} />
      {q.isLoading ? (
        <SkeletonCards count={4} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : d ? (
        <>
          <Card className={d.outstanding > 0 ? 'border-red-200 dark:border-red-900' : 'border-emerald-200 dark:border-emerald-900'}>
            <CardBody className="flex flex-wrap items-center justify-between gap-4">
              <div>
                <p className="text-sm text-slate-500 dark:text-slate-400">Amount due</p>
                <p className="text-3xl font-semibold tabular-nums text-slate-900 dark:text-white">{formatMoney(d.outstanding)}</p>
                <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">
                  {d.outstanding > 0 ? (d.nextDueDate ? `Next due ${formatDate(d.nextDueDate)}` : 'Please clear your dues') : 'You are all paid up'}
                </p>
              </div>
              <LinkButton to="/me/bills" variant={d.outstanding > 0 ? 'primary' : 'outline'}>
                View bills
              </LinkButton>
            </CardBody>
          </Card>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <KpiCard label="Your bed" value={d.roomNumber ? `${d.roomNumber}-${d.bedCode}` : '—'} hint={d.roomNumber ? `Room ${d.roomNumber}, bed ${d.bedCode}` : 'Not checked in'} icon={<BedDouble className="h-4 w-4" />} />
            <KpiCard label="Monthly rent" value={formatMoney(d.monthlyRent)} icon={<IndianRupee className="h-4 w-4" />} />
            <KpiCard label="Staying since" value={formatDate(d.stayingSince)} hint={<StatusBadge status={d.status} />} icon={<CalendarClock className="h-4 w-4" />} />
            <KpiCard label="Deposit held" value={formatMoney(d.depositHeld)} icon={<ShieldCheck className="h-4 w-4" />} />
            <KpiCard label="Open requests" value={d.openMaintenanceRequests} icon={<Wrench className="h-4 w-4" />} to="/maintenance" />
            <KpiCard label="Open complaints" value={d.openComplaints} icon={<MessageSquareWarning className="h-4 w-4" />} to="/complaints" />
          </div>
          <div className="grid gap-2 sm:grid-cols-2">
            <LinkButton to="/maintenance/new" variant="secondary" icon={<Wrench className="h-4 w-4" aria-hidden />}>
              Report a problem
            </LinkButton>
            <LinkButton to="/complaints/new" variant="secondary" icon={<MessageSquareWarning className="h-4 w-4" aria-hidden />}>
              Raise a complaint
            </LinkButton>
          </div>
          <Card>
            <CardHeader title="Recent payments" actions={<Link to="/me/bills" className="text-sm font-medium text-brand-700 hover:underline dark:text-brand-300">All bills</Link>} />
            <CardBody className="p-0">
              {d.recentPayments.length === 0 ? (
                <div className="p-4">
                  <EmptyState title="No payments yet" />
                </div>
              ) : (
                <ul className="divide-y divide-slate-100 dark:divide-slate-800">
                  {d.recentPayments.map((p) => (
                    <li key={p.paymentId} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
                      <span>
                        <span className="block font-medium tabular-nums">{formatMoney(p.amount)}</span>
                        <span className="block text-xs text-slate-500">
                          {p.receiptNumber} · {methodLabel(p.method)} · {formatDateTime(p.paidAt)}
                        </span>
                      </span>
                      <ReceiptButton paymentId={p.paymentId} receiptNumber={p.receiptNumber} />
                    </li>
                  ))}
                </ul>
              )}
            </CardBody>
          </Card>
        </>
      ) : null}
    </div>
  );
}
