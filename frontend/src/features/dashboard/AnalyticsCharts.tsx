import { useState, type ReactNode } from 'react';
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { BarChart3, Table2 } from 'lucide-react';
import { ApiError } from '@/api/errors';
import { Card, CardBody, CardHeader } from '@/components/ui/Card';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { formatMoney, formatMoneyCompact, formatMonth, formatMonthShort, formatNumber, formatPercent } from '@/lib/format';
import { useOccupancyReport, usePaymentsReport, usePropertiesReport, useRevenueReport, type AnalyticsParams } from './api';
import { useChartTheme } from './chartTheme';

function ChartFrame({ title, subtitle, children, query, table }: { title: string; subtitle?: ReactNode; children: ReactNode; query: { isLoading: boolean; error: unknown; refetch: () => unknown }; table?: ReactNode }) {
  const [showTable, setShowTable] = useState(false);
  return (
    <Card className="min-w-0">
      <CardHeader
        title={title}
        subtitle={subtitle}
        actions={
          table && !query.isLoading && !query.error ? (
            <Button variant="ghost" size="sm" onClick={() => setShowTable((s) => !s)} aria-pressed={showTable} icon={showTable ? <BarChart3 className="h-4 w-4" aria-hidden /> : <Table2 className="h-4 w-4" aria-hidden />}>
              {showTable ? 'Chart' : 'Table'}
            </Button>
          ) : undefined
        }
      />
      <CardBody>
        {query.isLoading ? (
          <Skeleton className="h-56 w-full" />
        ) : query.error ? (
          <AnalyticsUnavailable error={query.error} onRetry={() => void query.refetch()} />
        ) : showTable && table ? (
          <div className="max-h-72 overflow-auto">{table}</div>
        ) : (
          children
        )}
      </CardBody>
    </Card>
  );
}

function AnalyticsUnavailable({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const api = error instanceof ApiError ? error : null;
  const down = !api || api.status === 0 || api.status >= 500;
  return (
    <div className="flex h-56 flex-col items-center justify-center gap-2 rounded-xl bg-slate-50 px-4 text-center text-sm dark:bg-slate-800/50">
      <p className="font-medium text-slate-700 dark:text-slate-200">{down ? 'Analytics is temporarily unavailable' : 'Analytics could not be loaded'}</p>
      <p className="text-slate-500 dark:text-slate-400">
        {down ? 'Core operations are unaffected. Try again in a moment.' : api?.message}
        {api?.correlationId && <span className="mt-1 block font-mono text-xs">Ref: {api.correlationId}</span>}
      </p>
      <Button variant="outline" size="sm" onClick={onRetry}>
        Retry
      </Button>
    </div>
  );
}

function MiniTable({ head, rows }: { head: string[]; rows: (string | number)[][] }) {
  return (
    <table className="w-full text-sm">
      <thead>
        <tr className="text-left text-xs uppercase text-slate-500 dark:text-slate-400">
          {head.map((h, i) => (
            <th key={h} scope="col" className={'py-1.5 pr-2 font-medium ' + (i > 0 ? 'text-right' : '')}>
              {h}
            </th>
          ))}
        </tr>
      </thead>
      <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
        {rows.map((r, i) => (
          <tr key={i}>
            {r.map((c, j) => (
              <td key={j} className={'py-1.5 pr-2 text-slate-700 dark:text-slate-300 ' + (j > 0 ? 'text-right tabular-nums' : '')}>
                {c}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

const tooltipStyle = (t: ReturnType<typeof useChartTheme>) => ({
  contentStyle: { background: t.surface, border: `1px solid ${t.grid}`, borderRadius: 12, color: t.text, fontSize: 12 },
  labelStyle: { color: t.text, fontWeight: 600 },
  itemStyle: { color: t.text },
});

export function OccupancyTrendChart({ params }: { params: AnalyticsParams }) {
  const t = useChartTheme();
  const q = useOccupancyReport(params);
  const data = (q.data?.monthlyTrend ?? []).filter((m) => m.daysCounted > 0);
  const snap = q.data?.snapshot;
  return (
    <ChartFrame
      title="Occupancy trend"
      subtitle={snap ? `Now ${formatPercent(snap.occupancyRatePct)} · ${snap.occupiedBeds}/${snap.totalBeds - snap.maintenanceBeds} sellable beds` : 'Monthly bed-day occupancy'}
      query={q}
      table={<MiniTable head={['Month', 'Occupancy', 'Avg occupied beds']} rows={data.map((m) => [formatMonth(m.month), formatPercent(m.occupancyRatePct), formatNumber(m.averageOccupiedBeds)])} />}
    >
      <div className="h-56" role="img" aria-label="Line chart of monthly occupancy rate">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
            <defs>
              <linearGradient id="occFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={t.series1} stopOpacity={0.25} />
                <stop offset="100%" stopColor={t.series1} stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid stroke={t.grid} vertical={false} />
            <XAxis dataKey="month" tickFormatter={formatMonthShort} tick={{ fill: t.axis, fontSize: 11 }} tickLine={false} axisLine={false} minTickGap={16} />
            <YAxis domain={[0, 100]} tickFormatter={(v: number) => `${v}%`} tick={{ fill: t.axis, fontSize: 11 }} tickLine={false} axisLine={false} width={40} />
            <Tooltip {...tooltipStyle(t)} labelFormatter={(l: string) => formatMonth(l)} formatter={(v) => [formatPercent(Number(v)), 'Occupancy']} cursor={{ stroke: t.axis, strokeDasharray: '3 3' }} />
            <Area type="monotone" dataKey="occupancyRatePct" stroke={t.series1} strokeWidth={2} fill="url(#occFill)" dot={false} activeDot={{ r: 4, stroke: t.surface, strokeWidth: 2 }} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </ChartFrame>
  );
}

export function RevenueChart({ params }: { params: AnalyticsParams }) {
  const t = useChartTheme();
  const q = useRevenueReport(params);
  const data = q.data?.monthly ?? [];
  const totals = q.data?.totals;
  return (
    <ChartFrame
      title="Rent: billed vs collected"
      subtitle={totals ? `Net rent ${formatMoneyCompact(totals.netRentRevenue)} · collection ${formatPercent(totals.collectionRatePct)}` : 'Monthly rent revenue'}
      query={q}
      table={
        <MiniTable
          head={['Month', 'Billed', 'Net collected', 'Collection']}
          rows={data.map((m) => [formatMonth(m.month), formatMoney(m.billedRent), formatMoney(m.netRentRevenue), formatPercent(m.collectionRatePct)])}
        />
      }
    >
      <div className="h-56" role="img" aria-label="Line chart comparing billed rent and net rent collected per month">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: -4 }}>
            <CartesianGrid stroke={t.grid} vertical={false} />
            <XAxis dataKey="month" tickFormatter={formatMonthShort} tick={{ fill: t.axis, fontSize: 11 }} tickLine={false} axisLine={false} minTickGap={16} />
            <YAxis tickFormatter={(v: number) => formatMoneyCompact(v)} tick={{ fill: t.axis, fontSize: 11 }} tickLine={false} axisLine={false} width={56} />
            <Tooltip {...tooltipStyle(t)} labelFormatter={(l: string) => formatMonth(l)} formatter={(v, name) => [formatMoney(Number(v)), String(name)]} cursor={{ stroke: t.axis, strokeDasharray: '3 3' }} />
            <Legend iconType="plainline" wrapperStyle={{ fontSize: 12, color: t.axis }} />
            <Line type="monotone" name="Billed rent" dataKey="billedRent" stroke={t.series2} strokeWidth={2} dot={false} activeDot={{ r: 4, stroke: t.surface, strokeWidth: 2 }} />
            <Line type="monotone" name="Net collected" dataKey="netRentRevenue" stroke={t.series1} strokeWidth={2} dot={false} activeDot={{ r: 4, stroke: t.surface, strokeWidth: 2 }} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </ChartFrame>
  );
}

export function AgingChart({ params }: { params: AnalyticsParams }) {
  const t = useChartTheme();
  const q = usePaymentsReport(params);
  const data = (q.data?.aging ?? []).map((a) => ({ ...a, short: a.bucket === 'CURRENT' ? 'Not due' : a.label.replace(' days past due', 'd') }));
  const s = q.data?.summary;
  return (
    <ChartFrame
      title="Receivables aging"
      subtitle={s ? `${formatMoneyCompact(s.outstandingTotal)} outstanding · ${s.openInvoiceCount} open invoices` : 'Outstanding by days past due'}
      query={q}
      table={<MiniTable head={['Bucket', 'Invoices', 'Outstanding', 'Share']} rows={data.map((a) => [a.label, a.invoiceCount, formatMoney(a.outstandingAmount), formatPercent(a.sharePct)])} />}
    >
      <div className="h-56" role="img" aria-label="Bar chart of outstanding amount by aging bucket">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data} layout="vertical" margin={{ top: 4, right: 16, bottom: 0, left: 4 }} barCategoryGap={6}>
            <CartesianGrid stroke={t.grid} horizontal={false} />
            <XAxis type="number" tickFormatter={(v: number) => formatMoneyCompact(v)} tick={{ fill: t.axis, fontSize: 11 }} tickLine={false} axisLine={false} />
            <YAxis type="category" dataKey="short" tick={{ fill: t.axis, fontSize: 11 }} tickLine={false} axisLine={false} width={60} />
            <Tooltip
              {...tooltipStyle(t)}
              cursor={{ fill: t.grid, opacity: 0.4 }}
              labelFormatter={(_l: string, payload) => (payload?.[0]?.payload as { label?: string } | undefined)?.label ?? ''}
              formatter={(v, _n, item) => [`${formatMoney(Number(v))} · ${(item.payload as { invoiceCount: number }).invoiceCount} invoices`, 'Outstanding']}
            />
            <Bar dataKey="outstandingAmount" radius={[0, 4, 4, 0]} maxBarSize={22}>
              {data.map((_, i) => (
                <Cell key={i} fill={t.ramp[i] ?? t.series1} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </ChartFrame>
  );
}

export function PropertyComparison({ params }: { params: AnalyticsParams }) {
  const [sortBy, setSortBy] = useState('occupancyRate');
  const q = usePropertiesReport({ ...params, sortBy, order: sortBy === 'name' ? 'asc' : 'desc' });
  const rows = q.data?.properties ?? [];
  return (
    <ChartFrame title="Property comparison" subtitle={q.data ? `Current month ${formatMonth(q.data.currentMonth)}` : undefined} query={q}>
      <div className="mb-3 flex items-center gap-2">
        <label htmlFor="cmp-sort" className="text-sm text-slate-500 dark:text-slate-400">
          Rank by
        </label>
        <select
          id="cmp-sort"
          value={sortBy}
          onChange={(e) => setSortBy(e.target.value)}
          className="min-h-touch rounded-xl border border-slate-300 bg-white px-2 text-sm dark:border-slate-700 dark:bg-slate-900 md:min-h-[36px]"
        >
          <option value="occupancyRate">Occupancy</option>
          <option value="currentMonthRevenue">Revenue (month)</option>
          <option value="revenuePerBed">Revenue per bed</option>
          <option value="collectionRate">Collection rate</option>
          <option value="outstanding">Outstanding</option>
          <option value="openMaintenance">Open maintenance</option>
          <option value="slaCompliance">SLA compliance</option>
          <option value="name">Name</option>
        </select>
      </div>
      {rows.length === 0 ? (
        <p className="text-sm text-slate-500">No properties in scope.</p>
      ) : (
        <>
          <ul className="space-y-2 md:hidden">
            {rows.map((r) => (
              <li key={r.propertyId} className="rounded-xl border border-slate-200 p-3 dark:border-slate-800">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium text-slate-900 dark:text-slate-100">
                    #{r.rank} {r.propertyName}
                  </span>
                  <span className="text-sm tabular-nums text-slate-600 dark:text-slate-300">{formatPercent(r.occupancyRatePct)}</span>
                </div>
                <dl className="mt-2 grid grid-cols-2 gap-1 text-xs text-slate-600 dark:text-slate-400">
                  <div>
                    <dt className="inline">Revenue: </dt>
                    <dd className="inline">{formatMoneyCompact(r.currentMonthRevenue)}</dd>
                  </div>
                  <div>
                    <dt className="inline">Collection: </dt>
                    <dd className="inline">{formatPercent(r.collectionRatePct)}</dd>
                  </div>
                  <div>
                    <dt className="inline">Outstanding: </dt>
                    <dd className="inline">{formatMoneyCompact(r.outstandingAmount)}</dd>
                  </div>
                  <div>
                    <dt className="inline">Open tickets: </dt>
                    <dd className="inline">
                      {r.openMaintenance} / {r.openComplaints}
                    </dd>
                  </div>
                </dl>
              </li>
            ))}
          </ul>
          <div className="hidden overflow-x-auto md:block">
            <table className="min-w-full text-sm">
              <caption className="sr-only">Property comparison</caption>
              <thead>
                <tr className="border-b border-slate-200 text-left text-xs uppercase tracking-wide text-slate-500 dark:border-slate-800 dark:text-slate-400">
                  <th scope="col" className="py-2 pr-3">#</th>
                  <th scope="col" className="py-2 pr-3">Property</th>
                  <th scope="col" className="py-2 pr-3 text-right">Beds</th>
                  <th scope="col" className="py-2 pr-3 text-right">Occupancy</th>
                  <th scope="col" className="py-2 pr-3 text-right">Revenue (month)</th>
                  <th scope="col" className="py-2 pr-3 text-right">Per bed</th>
                  <th scope="col" className="py-2 pr-3 text-right">Collection</th>
                  <th scope="col" className="py-2 pr-3 text-right">Outstanding</th>
                  <th scope="col" className="py-2 pr-3 text-right">Maint. open</th>
                  <th scope="col" className="py-2 pr-3 text-right">SLA</th>
                  <th scope="col" className="py-2 text-right">Complaints</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 tabular-nums dark:divide-slate-800">
                {rows.map((r) => (
                  <tr key={r.propertyId} className="text-slate-700 dark:text-slate-300">
                    <td className="py-2 pr-3">{r.rank}</td>
                    <td className="py-2 pr-3 font-medium text-slate-900 dark:text-slate-100">
                      {r.propertyName}
                      <span className="block text-xs font-normal text-slate-500">{r.city}</span>
                    </td>
                    <td className="py-2 pr-3 text-right">
                      {r.occupiedBeds}/{r.totalBeds}
                    </td>
                    <td className="py-2 pr-3 text-right">{formatPercent(r.occupancyRatePct)}</td>
                    <td className="py-2 pr-3 text-right">{formatMoney(r.currentMonthRevenue)}</td>
                    <td className="py-2 pr-3 text-right">{formatMoney(r.revenuePerBed)}</td>
                    <td className="py-2 pr-3 text-right">{formatPercent(r.collectionRatePct)}</td>
                    <td className="py-2 pr-3 text-right">{formatMoney(r.outstandingAmount)}</td>
                    <td className="py-2 pr-3 text-right">{r.openMaintenance}</td>
                    <td className="py-2 pr-3 text-right">{formatPercent(r.slaCompliancePct)}</td>
                    <td className="py-2 text-right">{r.openComplaints}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </ChartFrame>
  );
}
