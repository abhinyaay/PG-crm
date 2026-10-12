import type { MaintenanceStatus, Role } from '@/api/types';

/** Mirror of the backend MaintenanceStatus state machine. */
export const MAINTENANCE_NEXT: Record<MaintenanceStatus, MaintenanceStatus[]> = {
  OPEN: ['ASSIGNED', 'CANCELLED'],
  ASSIGNED: ['IN_PROGRESS', 'OPEN', 'CANCELLED'],
  IN_PROGRESS: ['RESOLVED'],
  RESOLVED: ['CLOSED', 'IN_PROGRESS'],
  CLOSED: [],
  CANCELLED: [],
};

export const ACTIVE_MAINTENANCE: MaintenanceStatus[] = ['OPEN', 'ASSIGNED', 'IN_PROGRESS'];

export interface TransitionContext {
  role: Role;
  userId: number;
  assignedToUserId?: number | null;
}

/**
 * Status changes the current user may request (ASSIGNED is excluded: it goes through POST /assign).
 * Role rules match MaintenanceService.changeStatus:
 *  - TENANT: close, cancel (only while OPEN), or reopen (RESOLVED -> IN_PROGRESS);
 *  - STAFF: start / resolve, only on tickets assigned to them;
 *  - managers/admins: any valid transition.
 */
export function allowedMaintenanceTransitions(current: MaintenanceStatus, ctx: TransitionContext): MaintenanceStatus[] {
  const next = MAINTENANCE_NEXT[current].filter((s) => s !== 'ASSIGNED');
  switch (ctx.role) {
    case 'TENANT':
      return next.filter((s) => s === 'CLOSED' || s === 'IN_PROGRESS' || (s === 'CANCELLED' && current === 'OPEN'));
    case 'STAFF':
      if (ctx.assignedToUserId !== ctx.userId) return [];
      return next.filter((s) => s === 'IN_PROGRESS' || s === 'RESOLVED');
    default:
      return next;
  }
}

export function transitionLabel(from: MaintenanceStatus, to: MaintenanceStatus): string {
  if (to === 'IN_PROGRESS') return from === 'RESOLVED' ? 'Reopen' : 'Start work';
  if (to === 'RESOLVED') return 'Mark resolved';
  if (to === 'CLOSED') return 'Close';
  if (to === 'CANCELLED') return 'Cancel request';
  if (to === 'OPEN') return 'Unassign';
  return to;
}

/** Resolution notes are mandatory when resolving. */
export const requiresNotes = (to: MaintenanceStatus) => to === 'RESOLVED';
