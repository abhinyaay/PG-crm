import { AlarmClock, AlertTriangle, CheckCircle2 } from 'lucide-react';
import { Badge } from '@/components/ui/Badge';
import { formatDateTime, formatRelative } from '@/lib/format';

const ACTIVE = new Set(['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ESCALATED']);

/** SLA state: breached (red), due within 4h (amber), on track (gray), met (green). Icon + text, never color alone. */
export function SlaBadge({ dueAt, breached, status, now = new Date() }: { dueAt?: string | null; breached?: boolean; status: string; now?: Date }) {
  if (!dueAt) return null;
  const due = new Date(dueAt).getTime();
  const active = ACTIVE.has(status);
  const title = `SLA due ${formatDateTime(dueAt)}`;
  if (breached || (active && due < now.getTime())) {
    return (
      <span title={title}>
        <Badge tone="red" icon={<AlertTriangle className="h-3 w-3" aria-hidden />}>
          {active ? `Overdue ${formatRelative(dueAt, now).replace(' ago', '')}` : 'SLA missed'}
        </Badge>
      </span>
    );
  }
  if (!active) {
    return (
      <span title={title}>
        <Badge tone="green" icon={<CheckCircle2 className="h-3 w-3" aria-hidden />}>
          SLA met
        </Badge>
      </span>
    );
  }
  const soon = due - now.getTime() < 4 * 3600_000;
  return (
    <span title={title}>
      <Badge tone={soon ? 'amber' : 'gray'} icon={<AlarmClock className="h-3 w-3" aria-hidden />}>
        Due {formatRelative(dueAt, now)}
      </Badge>
    </span>
  );
}
