import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { UserPlus } from 'lucide-react';
import type { MaintenanceStatus } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { Button } from '@/components/ui/Button';
import { Card, CardBody, CardHeader, DescriptionList } from '@/components/ui/Card';
import { Select, Textarea } from '@/components/ui/Field';
import { Modal } from '@/components/ui/Modal';
import { PageHeader } from '@/components/ui/PageHeader';
import { Skeleton, SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { Timeline } from '@/components/ui/Timeline';
import { useToast } from '@/components/ui/Toast';
import { usePropertyName, usePropertyStaff, useUserLabel } from '@/features/shared/lookups';
import { TenantName } from '@/features/shared/TenantName';
import { RoomLabel } from '@/features/rooms/RoomLabel';
import { formatDateTime, humanize } from '@/lib/format';
import { isOperator } from '@/lib/roles';
import { useAssignMaintenance, useChangeMaintenanceStatus, useMaintenance, useMaintenanceHistory } from './api';
import { SlaBadge } from './SlaBadge';
import { allowedMaintenanceTransitions, requiresNotes, transitionLabel } from './transitions';

function ActorName({ id, propertyId }: { id?: number; propertyId: number }) {
  return <>{useUserLabel(id, propertyId)}</>;
}

export default function MaintenanceDetailPage() {
  const id = Number(useParams().id);
  const user = useUser();
  const toast = useToast();
  const q = useMaintenance(id);
  const history = useMaintenanceHistory(id);
  const t = q.data;
  const propertyName = usePropertyName(t?.propertyId);
  const assignee = useUserLabel(t?.assignedToUserId, t?.propertyId);
  const staff = usePropertyStaff(t?.propertyId);
  const assign = useAssignMaintenance(id);
  const changeStatus = useChangeMaintenanceStatus(id);
  const [target, setTarget] = useState<MaintenanceStatus | null>(null);
  const [notes, setNotes] = useState('');
  const [notesError, setNotesError] = useState<string | null>(null);
  const [assignOpen, setAssignOpen] = useState(false);
  const [assigneeId, setAssigneeId] = useState('');

  if (q.isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-10 w-2/3" />
        <Skeleton className="h-48" />
      </div>
    );
  }
  if (q.error || !t) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;

  const transitions = allowedMaintenanceTransitions(t.status, { role: user.role, userId: user.id, assignedToUserId: t.assignedToUserId });
  const canAssign = isOperator(user.role) && ['OPEN', 'ASSIGNED'].includes(t.status);
  const assignable = (staff.data ?? []).filter((a) => a.role === 'STAFF' || a.role === 'MANAGER');

  const doTransition = async () => {
    if (!target) return;
    if (requiresNotes(target) && notes.trim() === '') {
      setNotesError('Resolution notes are required');
      return;
    }
    try {
      await changeStatus.mutateAsync({ status: target, notes: notes.trim() || undefined });
      toast.success(`Status changed to ${humanize(target)}`);
      setTarget(null);
      setNotes('');
    } catch (e) {
      toast.apiError(e, 'Could not change status');
    }
  };

  return (
    <div className="space-y-4">
      <PageHeader
        back={{ to: '/maintenance', label: 'Maintenance' }}
        title={t.title}
        subtitle={
          <span className="flex flex-wrap items-center gap-1.5">
            {t.ticketNumber} <StatusBadge status={t.status} /> <StatusBadge status={t.priority} /> <SlaBadge dueAt={t.slaDueAt} breached={t.slaBreached} status={t.status} />
          </span>
        }
      />

      {(transitions.length > 0 || canAssign) && (
        <div className="sticky bottom-[calc(4rem+env(safe-area-inset-bottom))] z-20 -mx-4 flex flex-wrap gap-2 border-t border-slate-200 bg-white/95 px-4 py-3 backdrop-blur dark:border-slate-800 dark:bg-slate-950/95 md:static md:mx-0 md:border-0 md:bg-transparent md:p-0">
          {canAssign && (
            <Button variant="secondary" onClick={() => { setAssigneeId(t.assignedToUserId ? String(t.assignedToUserId) : ''); setAssignOpen(true); }} icon={<UserPlus className="h-4 w-4" aria-hidden />}>
              {t.assignedToUserId ? 'Reassign' : 'Assign'}
            </Button>
          )}
          {transitions.map((s) => (
            <Button key={s} variant={s === 'OPEN' || s === 'CANCELLED' || (t.status === 'RESOLVED' && s === 'IN_PROGRESS') ? 'outline' : 'primary'} onClick={() => { setNotes(''); setNotesError(null); setTarget(s); }}>
              {transitionLabel(t.status, s)}
            </Button>
          ))}
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader title="Details" />
          <CardBody className="space-y-4">
            <p className="whitespace-pre-line text-sm text-slate-700 dark:text-slate-200">{t.description}</p>
            <DescriptionList
              items={[
                { label: 'Category', value: humanize(t.category) },
                ...(user.role === 'TENANT' ? [] : [{ label: 'Property', value: propertyName }]),
                { label: 'Room', value: <RoomLabel id={t.roomId} /> },
                ...(t.tenantId && user.role !== 'TENANT' ? [{ label: 'Raised by resident', value: <TenantName id={t.tenantId} /> }] : []),
                { label: 'Assigned to', value: t.assignedToUserId ? assignee : 'Unassigned' },
                { label: 'Raised', value: formatDateTime(t.createdAt) },
                { label: 'SLA due', value: formatDateTime(t.slaDueAt) },
                { label: 'Started', value: formatDateTime(t.startedAt) },
                { label: 'Resolved', value: formatDateTime(t.resolvedAt) },
                { label: 'Closed', value: formatDateTime(t.closedAt) },
                ...(t.resolutionNotes ? [{ label: 'Resolution notes', value: t.resolutionNotes, full: true }] : []),
              ]}
            />
          </CardBody>
        </Card>
        <Card>
          <CardHeader title="History" />
          <CardBody>
            {history.isLoading ? (
              <SkeletonList rows={3} />
            ) : history.error ? (
              <ErrorState error={history.error} onRetry={() => void history.refetch()} />
            ) : !history.data?.length ? (
              <EmptyState title="No history yet" />
            ) : (
              <Timeline
                items={history.data.map((h, i) => ({
                  key: i,
                  title: h.fromStatus ? `${humanize(h.fromStatus)} → ${humanize(h.toStatus)}` : humanize(h.toStatus),
                  body: h.note,
                  at: h.changedAt,
                  meta: <ActorName id={h.changedBy} propertyId={t.propertyId} />,
                }))}
              />
            )}
          </CardBody>
        </Card>
      </div>

      <Modal
        open={target !== null}
        onClose={() => setTarget(null)}
        dismissible={!changeStatus.isPending}
        title={target ? transitionLabel(t.status, target) : ''}
        description={target ? `${humanize(t.status)} → ${humanize(target)}` : undefined}
        footer={
          <>
            <Button variant="outline" onClick={() => setTarget(null)} disabled={changeStatus.isPending}>
              Back
            </Button>
            <Button variant={target === 'CANCELLED' ? 'danger' : 'primary'} loading={changeStatus.isPending} onClick={() => void doTransition()}>
              Confirm
            </Button>
          </>
        }
      >
        <Textarea
          label={target && requiresNotes(target) ? 'Resolution notes' : 'Note (optional)'}
          required={!!target && requiresNotes(target)}
          rows={4}
          maxLength={4000}
          value={notes}
          onChange={(e) => {
            setNotes(e.target.value);
            setNotesError(null);
          }}
          error={notesError ?? undefined}
          data-autofocus
        />
      </Modal>

      <Modal
        open={assignOpen}
        onClose={() => setAssignOpen(false)}
        dismissible={!assign.isPending}
        title="Assign ticket"
        description="Only staff and managers assigned to this property can take the ticket."
        footer={
          <>
            <Button variant="outline" onClick={() => setAssignOpen(false)} disabled={assign.isPending}>
              Cancel
            </Button>
            <Button
              loading={assign.isPending}
              disabled={!assigneeId}
              onClick={async () => {
                try {
                  await assign.mutateAsync(Number(assigneeId));
                  toast.success('Ticket assigned');
                  setAssignOpen(false);
                } catch (e) {
                  toast.apiError(e, 'Could not assign');
                }
              }}
            >
              Assign
            </Button>
          </>
        }
      >
        {staff.isLoading ? (
          <Skeleton className="h-11" />
        ) : assignable.length === 0 ? (
          <EmptyState title="No staff assigned to this property" description="Assign staff on the property page first." />
        ) : (
          <Select label="Assignee" value={assigneeId} onChange={(e) => setAssigneeId(e.target.value)} data-autofocus>
            <option value="">Select a person…</option>
            {assignable.map((a) => (
              <option key={a.userId} value={a.userId}>
                {a.fullName} ({humanize(a.role)})
              </option>
            ))}
          </Select>
        )}
      </Modal>
    </div>
  );
}
