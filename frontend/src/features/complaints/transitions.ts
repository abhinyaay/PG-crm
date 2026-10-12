import type { ComplaintStatus, Role } from '@/api/types';

/** Mirror of the backend ComplaintStatus state machine. */
export const COMPLAINT_NEXT: Record<ComplaintStatus, ComplaintStatus[]> = {
  OPEN: ['IN_PROGRESS', 'ESCALATED', 'RESOLVED', 'CLOSED'],
  IN_PROGRESS: ['ESCALATED', 'RESOLVED'],
  ESCALATED: ['IN_PROGRESS', 'RESOLVED'],
  RESOLVED: ['CLOSED', 'OPEN'],
  CLOSED: [],
};

export const ACTIVE_COMPLAINT: ComplaintStatus[] = ['OPEN', 'IN_PROGRESS', 'ESCALATED'];
export const MAX_ESCALATION_LEVEL = 3;

/**
 * Status changes offered to the user (escalation has its own endpoint/button). Mirrors ComplaintService:
 *  - TENANT: close, or reopen a RESOLVED complaint;
 *  - STAFF: only complaints assigned to them;
 *  - managers/admins: any valid transition.
 */
export function allowedComplaintTransitions(current: ComplaintStatus, ctx: { role: Role; userId: number; assignedToUserId?: number | null }): ComplaintStatus[] {
  const next = COMPLAINT_NEXT[current].filter((s) => s !== 'ESCALATED');
  if (ctx.role === 'TENANT') return next.filter((s) => s === 'CLOSED' || (s === 'OPEN' && current === 'RESOLVED'));
  if (ctx.role === 'STAFF' && ctx.assignedToUserId !== ctx.userId) return [];
  return next;
}

export function canEscalate(c: { status: ComplaintStatus; escalationLevel: number; dueAt?: string | null }, role: Role, now = new Date()): boolean {
  if (!ACTIVE_COMPLAINT.includes(c.status) || c.escalationLevel >= MAX_ESCALATION_LEVEL) return false;
  if (role === 'TENANT') return !!c.dueAt && new Date(c.dueAt).getTime() < now.getTime();
  return true;
}

export function complaintTransitionLabel(from: ComplaintStatus, to: ComplaintStatus): string {
  if (to === 'IN_PROGRESS') return 'Start working';
  if (to === 'RESOLVED') return 'Mark resolved';
  if (to === 'CLOSED') return 'Close';
  if (to === 'OPEN') return from === 'RESOLVED' ? 'Reopen' : 'Open';
  return to;
}
