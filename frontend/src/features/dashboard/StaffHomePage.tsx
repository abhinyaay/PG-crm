import { Link } from 'react-router-dom';
import { AlertTriangle, ClipboardList, MessageSquareWarning, Timer } from 'lucide-react';
import type { StaffDashboard } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { Card, CardBody, CardHeader } from '@/components/ui/Card';
import { KpiCard } from '@/components/ui/KpiCard';
import { PageHeader } from '@/components/ui/PageHeader';
import { SkeletonCards, SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { SlaBadge } from '@/features/maintenance/SlaBadge';
import { useDashboard } from './api';
import { MaintenanceQueue } from '@/features/maintenance/MaintenanceQueue';

export default function StaffHomePage() {
  const user = useUser();
  const q = useDashboard<StaffDashboard>();
  const d = q.data;
  return (
    <div className="space-y-6">
      <PageHeader title={`Hi, ${user.fullName.split(' ')[0]}`} subtitle="Your work queue" />
      {q.isLoading ? (
        <SkeletonCards count={4} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : d ? (
        <section className="grid grid-cols-2 gap-3 lg:grid-cols-4" aria-label="Work summary">
          <KpiCard label="Assigned & open" value={d.assignedOpen} icon={<ClipboardList className="h-4 w-4" />} to="/maintenance?mine=1" />
          <KpiCard label="In progress" value={d.inProgress} icon={<Timer className="h-4 w-4" />} to="/maintenance?mine=1&status=IN_PROGRESS" />
          <KpiCard label="Past SLA" value={d.slaBreached} icon={<AlertTriangle className="h-4 w-4" />} tone={d.slaBreached ? 'bad' : 'good'} to="/maintenance?mine=1&breached=1" />
          <KpiCard label="My complaints" value={d.assignedComplaints} icon={<MessageSquareWarning className="h-4 w-4" />} to="/complaints?mine=1" />
        </section>
      ) : null}

      <Card>
        <CardHeader title="Next up" subtitle="Highest-priority tickets assigned to you" />
        <CardBody className="p-0">
          {q.isLoading ? (
            <div className="p-4">
              <SkeletonList rows={3} />
            </div>
          ) : !d || d.nextTickets.length === 0 ? (
            <div className="p-4">
              <EmptyState title="Nothing in your queue" description="New tickets assigned to you will show up here." />
            </div>
          ) : (
            <ul className="divide-y divide-slate-100 dark:divide-slate-800">
              {d.nextTickets.map((t) => (
                <li key={t.id}>
                  <Link to={`/maintenance/${t.id}`} className="flex min-h-touch items-start justify-between gap-3 px-4 py-3 hover:bg-slate-50 dark:hover:bg-slate-800/50">
                    <span className="min-w-0">
                      <span className="block truncate font-medium text-slate-900 dark:text-slate-100">{t.title}</span>
                      <span className="mt-1 flex flex-wrap gap-1">
                        <span className="text-xs text-slate-500">{t.number}</span>
                        <StatusBadge status={t.priority} />
                        <StatusBadge status={t.status} />
                      </span>
                    </span>
                    <SlaBadge dueAt={t.dueAt} breached={t.breached} status={t.status} />
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </CardBody>
      </Card>

      <MaintenanceQueue title="All my open tickets" />
    </div>
  );
}
