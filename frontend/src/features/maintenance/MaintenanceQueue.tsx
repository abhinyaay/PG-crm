import { Link } from 'react-router-dom';
import { Card, CardBody, CardHeader } from '@/components/ui/Card';
import { StatusBadge } from '@/components/ui/Badge';
import { SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { useMaintenanceList } from './api';
import { SlaBadge } from './SlaBadge';
import { ACTIVE_MAINTENANCE } from './transitions';

/** Tickets assigned to the current user that are still active. */
export function MaintenanceQueue({ title }: { title: string }) {
  const q = useMaintenanceList({ assignedToMe: true, size: 50, sort: 'slaDueAt,asc' });
  const rows = q.data?.content.filter((t) => ACTIVE_MAINTENANCE.includes(t.status)) ?? [];
  return (
    <Card>
      <CardHeader title={title} actions={<Link to="/maintenance?mine=1" className="text-sm font-medium text-brand-700 hover:underline dark:text-brand-300">See all</Link>} />
      <CardBody className="p-0">
        {q.isLoading ? (
          <div className="p-4">
            <SkeletonList rows={3} />
          </div>
        ) : q.error ? (
          <div className="p-4">
            <ErrorState error={q.error} onRetry={() => void q.refetch()} />
          </div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No open tickets assigned to you" />
          </div>
        ) : (
          <ul className="divide-y divide-slate-100 dark:divide-slate-800">
            {rows.map((t) => (
              <li key={t.id}>
                <Link to={`/maintenance/${t.id}`} className="flex min-h-touch items-start justify-between gap-3 px-4 py-3 hover:bg-slate-50 dark:hover:bg-slate-800/50">
                  <span className="min-w-0">
                    <span className="block truncate font-medium text-slate-900 dark:text-slate-100">{t.title}</span>
                    <span className="mt-1 flex flex-wrap items-center gap-1 text-xs text-slate-500">
                      {t.ticketNumber} <StatusBadge status={t.priority} /> <StatusBadge status={t.status} />
                    </span>
                  </span>
                  <SlaBadge dueAt={t.slaDueAt} breached={t.slaBreached} status={t.status} />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </CardBody>
    </Card>
  );
}
