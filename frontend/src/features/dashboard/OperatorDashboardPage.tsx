import { useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, BedDouble, IndianRupee, MessageSquareWarning, ReceiptText, Users, Wrench } from 'lucide-react';
import type { OperationsDashboard } from '@/api/types';
import { Card, CardBody, CardHeader } from '@/components/ui/Card';
import { KpiCard } from '@/components/ui/KpiCard';
import { PageHeader } from '@/components/ui/PageHeader';
import { SkeletonCards, Skeleton } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { Badge } from '@/components/ui/Badge';
import { Select } from '@/components/ui/Field';
import { PropertySelect } from '@/features/shared/PropertySelect';
import { usePropertyName } from '@/features/shared/lookups';
import { addMonths, currentMonthInBusinessTz, formatDate, formatDateTime, formatMoney, formatMoneyCompact, formatNumber, formatPercent, todayInBusinessTz, methodLabel } from '@/lib/format';
import { useDashboard } from './api';
import { AgingChart, OccupancyTrendChart, PropertyComparison, RevenueChart } from './AnalyticsCharts';

function PropertyLabel({ id }: { id: number }) {
  return <>{usePropertyName(id)}</>;
}

export default function OperatorDashboardPage() {
  const [propertyId, setPropertyId] = useState<number | undefined>();
  const [months, setMonths] = useState(12);
  const dashboard = useDashboard<OperationsDashboard>(propertyId);
  const d = dashboard.data;
  const to = currentMonthInBusinessTz();
  const analyticsParams = { propertyId, from: addMonths(to, -(months - 1)), to };
  const today = todayInBusinessTz();

  return (
    <div className="space-y-6">
      <PageHeader
        title="Dashboard"
        subtitle={d ? `Updated ${formatDateTime(d.generatedAt)}` : 'Operations overview'}
        actions={
          <div className="grid w-full grid-cols-2 gap-2 sm:w-auto">
            <PropertySelect label="Property" value={propertyId ?? ''} onChange={(e) => setPropertyId(e.target.value ? Number(e.target.value) : undefined)} containerClassName="min-w-0 sm:w-56" />
            <Select label="Period" value={months} onChange={(e) => setMonths(Number(e.target.value))} containerClassName="min-w-0 sm:w-40">
              <option value={6}>6 months</option>
              <option value={12}>12 months</option>
              <option value={24}>24 months</option>
            </Select>
          </div>
        }
      />

      {dashboard.isLoading ? (
        <SkeletonCards count={8} />
      ) : dashboard.error ? (
        <ErrorState error={dashboard.error} onRetry={() => void dashboard.refetch()} />
      ) : d ? (
        <section aria-label="Key figures" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          <KpiCard label="Occupancy" value={formatPercent(d.beds.occupancyRate)} hint={`${d.beds.occupied}/${d.beds.total} beds`} icon={<BedDouble className="h-4 w-4" />} to="/beds" />
          <KpiCard label="Available beds" value={formatNumber(d.beds.available)} hint={`${d.beds.reserved} reserved · ${d.beds.maintenance} maint.`} icon={<BedDouble className="h-4 w-4" />} tone="good" to="/beds" />
          <KpiCard label="Active tenants" value={formatNumber(d.activeTenants)} icon={<Users className="h-4 w-4" />} to="/tenants?status=ACTIVE" />
          <KpiCard label="Revenue this month" value={formatMoneyCompact(d.monthlyRevenue)} hint={formatMoney(d.monthlyRevenue)} icon={<IndianRupee className="h-4 w-4" />} tone="good" to="/payments" />
          <KpiCard label="Outstanding rent" value={formatMoneyCompact(d.outstandingRent)} hint={formatMoney(d.outstandingRent)} icon={<ReceiptText className="h-4 w-4" />} tone="warn" to="/invoices?status=OVERDUE" />
          <KpiCard label="Overdue invoices" value={formatNumber(d.overdueInvoices)} icon={<AlertTriangle className="h-4 w-4" />} tone={d.overdueInvoices > 0 ? 'bad' : 'good'} to="/invoices?status=OVERDUE" />
          <KpiCard
            label="Open maintenance"
            value={formatNumber(d.pendingMaintenance)}
            hint={`${d.slaBreachedMaintenance} past SLA`}
            icon={<Wrench className="h-4 w-4" />}
            tone={d.slaBreachedMaintenance > 0 ? 'bad' : 'default'}
            to="/maintenance"
          />
          <KpiCard
            label="Open complaints"
            value={formatNumber(d.openComplaints)}
            hint={`${d.escalatedComplaints} escalated`}
            icon={<MessageSquareWarning className="h-4 w-4" />}
            tone={d.escalatedComplaints > 0 ? 'warn' : 'default'}
            to="/complaints"
          />
        </section>
      ) : null}

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader title="Upcoming check-outs" subtitle="Residents on notice" />
          <CardBody className="p-0">
            {dashboard.isLoading ? (
              <div className="space-y-2 p-4">
                <Skeleton className="h-12" />
                <Skeleton className="h-12" />
              </div>
            ) : !d || d.upcomingCheckouts.length === 0 ? (
              <div className="p-4">
                <EmptyState title="No upcoming check-outs" />
              </div>
            ) : (
              <ul className="divide-y divide-slate-100 dark:divide-slate-800">
                {d.upcomingCheckouts.map((c) => (
                  <li key={c.allocationId}>
                    <Link to={`/tenants/${c.tenantId}`} className="flex min-h-touch items-center justify-between gap-3 px-4 py-3 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-500 dark:hover:bg-slate-800/50">
                      <span className="min-w-0">
                        <span className="block truncate font-medium text-slate-900 dark:text-slate-100">{c.tenantName}</span>
                        <span className="block truncate text-xs text-slate-500 dark:text-slate-400">
                          <PropertyLabel id={c.propertyId} /> · Room {c.roomNumber} · Bed {c.bedCode}
                        </span>
                      </span>
                      <span className="shrink-0 text-right text-sm">
                        <span className="block tabular-nums text-slate-700 dark:text-slate-300">{formatDate(c.expectedEndDate)}</span>
                        {c.expectedEndDate < today && <Badge tone="red">Overdue</Badge>}
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </CardBody>
        </Card>

        <Card>
          <CardHeader title="Recent payments" actions={<Link to="/payments" className="text-sm font-medium text-brand-700 hover:underline dark:text-brand-300">View all</Link>} />
          <CardBody className="p-0">
            {dashboard.isLoading ? (
              <div className="space-y-2 p-4">
                <Skeleton className="h-12" />
                <Skeleton className="h-12" />
              </div>
            ) : !d || d.recentPayments.length === 0 ? (
              <div className="p-4">
                <EmptyState title="No payments yet" />
              </div>
            ) : (
              <ul className="divide-y divide-slate-100 dark:divide-slate-800">
                {d.recentPayments.map((p) => (
                  <li key={p.paymentId}>
                    <Link to={`/payments/${p.paymentId}`} className="flex min-h-touch items-center justify-between gap-3 px-4 py-3 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-500 dark:hover:bg-slate-800/50">
                      <span className="min-w-0">
                        <span className="block truncate font-medium text-slate-900 dark:text-slate-100">{p.tenantName}</span>
                        <span className="block truncate text-xs text-slate-500 dark:text-slate-400">
                          {p.receiptNumber} · {methodLabel(p.method)} · {formatDateTime(p.paidAt)}
                        </span>
                      </span>
                      <span className="shrink-0 font-medium tabular-nums text-slate-900 dark:text-slate-100">{formatMoney(p.amount)}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </CardBody>
        </Card>
      </div>

      <section aria-labelledby="analytics-heading" className="space-y-4">
        <h2 id="analytics-heading" className="text-lg font-semibold text-slate-900 dark:text-slate-100">
          Analytics
        </h2>
        <div className="grid gap-4 lg:grid-cols-2">
          <OccupancyTrendChart params={analyticsParams} />
          <RevenueChart params={analyticsParams} />
          <AgingChart params={analyticsParams} />
          <PropertyComparison params={analyticsParams} />
        </div>
      </section>
    </div>
  );
}
