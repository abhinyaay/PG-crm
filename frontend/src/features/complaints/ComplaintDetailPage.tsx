import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { ArrowUpCircle, Lock, Send, UserPlus } from 'lucide-react';
import type { ComplaintStatus } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { Button } from '@/components/ui/Button';
import { Card, CardBody, CardHeader, DescriptionList } from '@/components/ui/Card';
import { Checkbox, Select, Textarea } from '@/components/ui/Field';
import { Modal } from '@/components/ui/Modal';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { PageHeader } from '@/components/ui/PageHeader';
import { Skeleton, SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { Badge, StatusBadge } from '@/components/ui/Badge';
import { Tabs } from '@/components/ui/Tabs';
import { Timeline } from '@/components/ui/Timeline';
import { useToast } from '@/components/ui/Toast';
import { SlaBadge } from '@/features/maintenance/SlaBadge';
import { usePropertyName, usePropertyStaff, useUserLabel } from '@/features/shared/lookups';
import { TenantName } from '@/features/shared/TenantName';
import { formatDateTime, humanize } from '@/lib/format';
import { isAdmin, isOperator } from '@/lib/roles';
import { useComplaint, useComplaintActions, useComplaintComments, useComplaintHistory } from './api';
import { allowedComplaintTransitions, canEscalate, complaintTransitionLabel, ACTIVE_COMPLAINT } from './transitions';

function Actor({ id, propertyId }: { id?: number; propertyId: number }) {
  return <>{useUserLabel(id, propertyId)}</>;
}

export default function ComplaintDetailPage() {
  const id = Number(useParams().id);
  const user = useUser();
  const isTenant = user.role === 'TENANT';
  const toast = useToast();
  const q = useComplaint(id);
  const comments = useComplaintComments(id);
  const history = useComplaintHistory(id);
  const actions = useComplaintActions(id);
  const c = q.data;
  const propertyName = usePropertyName(c?.propertyId);
  const assignee = useUserLabel(c?.assignedToUserId, c?.propertyId);
  const staff = usePropertyStaff(c?.propertyId);
  const [tab, setTab] = useState('comments');
  const [target, setTarget] = useState<ComplaintStatus | null>(null);
  const [resolution, setResolution] = useState('');
  const [resolutionError, setResolutionError] = useState<string | null>(null);
  const [escalateOpen, setEscalateOpen] = useState(false);
  const [assignOpen, setAssignOpen] = useState(false);
  const [assigneeId, setAssigneeId] = useState('');
  const [body, setBody] = useState('');
  const [internal, setInternal] = useState(false);

  const assignable = useMemo(() => {
    const list = (staff.data ?? []).filter((a) => a.role === 'STAFF' || a.role === 'MANAGER').map((a) => ({ id: a.userId, label: `${a.fullName} (${humanize(a.role)})` }));
    if (isAdmin(user.role) && !list.some((a) => a.id === user.id)) list.push({ id: user.id, label: `${user.fullName} (you)` });
    return list;
  }, [staff.data, user]);

  if (q.isLoading) return <Skeleton className="h-64" />;
  if (q.error || !c) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;

  const transitions = allowedComplaintTransitions(c.status, { role: user.role, userId: user.id, assignedToUserId: c.assignedToUserId });
  const escalatable = canEscalate(c, user.role);
  const canAssign = isOperator(user.role) && ACTIVE_COMPLAINT.includes(c.status);

  const submitTransition = async () => {
    if (!target) return;
    if (target === 'RESOLVED' && !resolution.trim()) {
      setResolutionError('A resolution summary is required');
      return;
    }
    try {
      await actions.status.mutateAsync({ status: target, resolution: resolution.trim() || undefined });
      toast.success(`Complaint ${humanize(target).toLowerCase()}`);
      setTarget(null);
    } catch (e) {
      toast.apiError(e, 'Could not update complaint');
    }
  };

  const postComment = async () => {
    if (!body.trim()) return;
    try {
      await actions.comment.mutateAsync({ body: body.trim(), internal: !isTenant && internal });
      setBody('');
      setInternal(false);
    } catch (e) {
      toast.apiError(e, 'Could not post comment');
    }
  };

  return (
    <div className="space-y-4">
      <PageHeader
        back={{ to: '/complaints', label: 'Complaints' }}
        title={c.subject}
        subtitle={
          <span className="flex flex-wrap items-center gap-1.5">
            {c.complaintNumber} <StatusBadge status={c.status} /> <StatusBadge status={c.priority} />
            {c.escalationLevel > 0 && <Badge tone="red">Escalation level {c.escalationLevel}</Badge>}
            <SlaBadge dueAt={c.dueAt} status={c.status} />
          </span>
        }
      />

      {(transitions.length > 0 || escalatable || canAssign) && (
        <div className="sticky bottom-[calc(4rem+env(safe-area-inset-bottom))] z-20 -mx-4 flex flex-wrap gap-2 border-t border-slate-200 bg-white/95 px-4 py-3 backdrop-blur dark:border-slate-800 dark:bg-slate-950/95 md:static md:mx-0 md:border-0 md:bg-transparent md:p-0">
          {canAssign && (
            <Button variant="secondary" icon={<UserPlus className="h-4 w-4" aria-hidden />} onClick={() => { setAssigneeId(c.assignedToUserId ? String(c.assignedToUserId) : ''); setAssignOpen(true); }}>
              {c.assignedToUserId ? 'Reassign' : 'Assign'}
            </Button>
          )}
          {transitions.map((s) => (
            <Button key={s} variant={s === 'CLOSED' ? 'outline' : 'primary'} onClick={() => { setResolution(''); setResolutionError(null); setTarget(s); }}>
              {complaintTransitionLabel(c.status, s)}
            </Button>
          ))}
          {escalatable && (
            <Button variant="danger" icon={<ArrowUpCircle className="h-4 w-4" aria-hidden />} onClick={() => setEscalateOpen(true)}>
              Escalate
            </Button>
          )}
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-1">
          <CardHeader title="Details" />
          <CardBody className="space-y-4">
            <p className="whitespace-pre-line text-sm text-slate-700 dark:text-slate-200">{c.description}</p>
            <DescriptionList
              className="sm:grid-cols-1"
              items={[
                { label: 'Category', value: humanize(c.category) },
                ...(isTenant ? [] : [{ label: 'Property', value: propertyName }]),
                ...(c.tenantId && !isTenant ? [{ label: 'Resident', value: <TenantName id={c.tenantId} /> }] : []),
                { label: 'Assigned to', value: c.assignedToUserId ? assignee : 'Unassigned' },
                { label: 'Raised', value: formatDateTime(c.createdAt) },
                { label: 'Resolution due', value: formatDateTime(c.dueAt) },
                ...(c.resolution ? [{ label: 'Resolution', value: c.resolution }] : []),
                ...(c.resolvedAt ? [{ label: 'Resolved', value: formatDateTime(c.resolvedAt) }] : []),
              ]}
            />
          </CardBody>
        </Card>

        <Card className="lg:col-span-2">
          <div className="px-4 pt-2">
            <Tabs
              value={tab}
              onChange={setTab}
              tabs={[
                { id: 'comments', label: 'Comments', count: comments.data?.length },
                { id: 'timeline', label: 'Timeline' },
              ]}
            />
          </div>
          <CardBody>
            {tab === 'comments' ? (
              <div className="space-y-4">
                {comments.isLoading ? (
                  <SkeletonList rows={2} />
                ) : comments.error ? (
                  <ErrorState error={comments.error} onRetry={() => void comments.refetch()} />
                ) : !comments.data?.length ? (
                  <EmptyState title="No comments yet" />
                ) : (
                  <ul className="space-y-3">
                    {comments.data.map((cm) => (
                      <li
                        key={cm.id}
                        className={
                          'rounded-2xl p-3 text-sm ' +
                          (cm.internal ? 'border border-dashed border-amber-300 bg-amber-50 dark:border-amber-800 dark:bg-amber-950/30' : 'bg-slate-50 dark:bg-slate-800/50')
                        }
                      >
                        <div className="mb-1 flex flex-wrap items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
                          <span className="font-medium text-slate-700 dark:text-slate-200">
                            <Actor id={cm.authorUserId} propertyId={c.propertyId} />
                          </span>
                          <span>{formatDateTime(cm.createdAt)}</span>
                          {cm.internal && (
                            <Badge tone="amber" icon={<Lock className="h-3 w-3" aria-hidden />}>
                              Internal note
                            </Badge>
                          )}
                        </div>
                        <p className="whitespace-pre-line text-slate-800 dark:text-slate-100">{cm.body}</p>
                      </li>
                    ))}
                  </ul>
                )}
                {c.status !== 'CLOSED' && (
                  <form
                    className="space-y-2 border-t border-slate-100 pt-4 dark:border-slate-800"
                    onSubmit={(e) => {
                      e.preventDefault();
                      void postComment();
                    }}
                  >
                    <Textarea label="Add a comment" rows={3} maxLength={4000} value={body} onChange={(e) => setBody(e.target.value)} />
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      {/* internal notes are a staff-only feature: never offered to residents */}
                      {!isTenant ? <Checkbox label="Internal note" description="Hidden from the resident" checked={internal} onChange={(e) => setInternal(e.target.checked)} /> : <span />}
                      <Button type="submit" loading={actions.comment.isPending} disabled={!body.trim()} icon={<Send className="h-4 w-4" aria-hidden />}>
                        Post
                      </Button>
                    </div>
                  </form>
                )}
              </div>
            ) : history.isLoading ? (
              <SkeletonList rows={3} />
            ) : history.error ? (
              <ErrorState error={history.error} onRetry={() => void history.refetch()} />
            ) : !history.data?.length ? (
              <EmptyState title="No activity" />
            ) : (
              <Timeline
                items={history.data.map((a, i) => ({
                  key: i,
                  title:
                    a.type === 'STATUS_CHANGED' && a.fromStatus && a.toStatus
                      ? `${humanize(a.fromStatus)} → ${humanize(a.toStatus)}`
                      : a.type === 'ESCALATED'
                        ? `Escalated${a.toStatus ? ` (${humanize(a.toStatus)})` : ''}`
                        : humanize(a.type),
                  body: a.details,
                  at: a.createdAt,
                  tone: a.type === 'ESCALATED' ? 'warn' : a.type === 'COMMENTED' ? 'muted' : 'default',
                  meta: <Actor id={a.actorUserId} propertyId={c.propertyId} />,
                }))}
              />
            )}
          </CardBody>
        </Card>
      </div>

      <Modal
        open={target !== null}
        onClose={() => setTarget(null)}
        dismissible={!actions.status.isPending}
        title={target ? complaintTransitionLabel(c.status, target) : ''}
        footer={
          <>
            <Button variant="outline" onClick={() => setTarget(null)}>
              Back
            </Button>
            <Button loading={actions.status.isPending} onClick={() => void submitTransition()}>
              Confirm
            </Button>
          </>
        }
      >
        {target === 'RESOLVED' ? (
          <Textarea
            label="Resolution summary"
            required
            rows={4}
            maxLength={4000}
            value={resolution}
            onChange={(e) => {
              setResolution(e.target.value);
              setResolutionError(null);
            }}
            error={resolutionError ?? undefined}
            data-autofocus
          />
        ) : (
          <p className="text-sm text-slate-600 dark:text-slate-300">
            Move this complaint from {humanize(c.status)} to {target ? humanize(target) : ''}?
          </p>
        )}
      </Modal>

      <ConfirmDialog
        open={escalateOpen}
        title="Escalate complaint"
        description={`This raises the complaint to level ${c.escalationLevel + 1} and notifies managers and admins.`}
        confirmLabel="Escalate"
        reasonLabel="Reason"
        reasonRequired
        loading={actions.escalate.isPending}
        onClose={() => setEscalateOpen(false)}
        onConfirm={async (reason) => {
          try {
            await actions.escalate.mutateAsync(reason);
            toast.success('Complaint escalated');
            setEscalateOpen(false);
          } catch (e) {
            toast.apiError(e, 'Could not escalate');
          }
        }}
      />

      <Modal
        open={assignOpen}
        onClose={() => setAssignOpen(false)}
        title="Assign complaint"
        footer={
          <>
            <Button variant="outline" onClick={() => setAssignOpen(false)}>
              Cancel
            </Button>
            <Button
              disabled={!assigneeId}
              loading={actions.assign.isPending}
              onClick={async () => {
                try {
                  await actions.assign.mutateAsync(Number(assigneeId));
                  toast.success('Complaint assigned');
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
        <Select label="Assignee" value={assigneeId} onChange={(e) => setAssigneeId(e.target.value)} data-autofocus>
          <option value="">Select a person…</option>
          {assignable.map((a) => (
            <option key={a.id} value={a.id}>
              {a.label}
            </option>
          ))}
        </Select>
      </Modal>
    </div>
  );
}
