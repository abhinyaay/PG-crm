import type { ReactNode } from 'react';
import { AlertTriangle, Inbox, RefreshCw } from 'lucide-react';
import { ApiError } from '@/api/errors';
import { Button } from './Button';
import { cn } from '@/lib/cn';

export function EmptyState({ title, description, icon, action, className }: { title: string; description?: ReactNode; icon?: ReactNode; action?: ReactNode; className?: string }) {
  return (
    <div className={cn('flex flex-col items-center justify-center rounded-2xl border border-dashed border-slate-300 px-6 py-10 text-center dark:border-slate-700', className)}>
      <div className="mb-3 rounded-full bg-slate-100 p-3 text-slate-500 dark:bg-slate-800 dark:text-slate-400">{icon ?? <Inbox className="h-6 w-6" aria-hidden />}</div>
      <p className="font-medium text-slate-900 dark:text-slate-100">{title}</p>
      {description && <p className="mt-1 max-w-sm text-sm text-slate-500 dark:text-slate-400">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry, title = 'Could not load data', className }: { error: unknown; onRetry?: () => void; title?: string; className?: string }) {
  const api = error instanceof ApiError ? error : null;
  const message = api?.message ?? (error instanceof Error ? error.message : 'Unexpected error');
  return (
    <div role="alert" className={cn('rounded-2xl border border-red-200 bg-red-50 p-4 text-sm dark:border-red-900 dark:bg-red-950/40', className)}>
      <div className="flex items-start gap-3">
        <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-red-600 dark:text-red-400" aria-hidden />
        <div className="min-w-0 flex-1">
          <p className="font-medium text-red-800 dark:text-red-200">{title}</p>
          <p className="mt-0.5 text-red-700 dark:text-red-300">{message}</p>
          {api?.correlationId && (
            <p className="mt-1 break-all font-mono text-xs text-red-600/80 dark:text-red-400/80">Ref: {api.correlationId}</p>
          )}
        </div>
        {onRetry && (
          <Button variant="outline" size="sm" onClick={onRetry} icon={<RefreshCw className="h-4 w-4" aria-hidden />}>
            Retry
          </Button>
        )}
      </div>
    </div>
  );
}

export function Spinner({ label = 'Loading…' }: { label?: string }) {
  return (
    <div role="status" className="flex items-center justify-center gap-2 p-8 text-sm text-slate-500">
      <span className="h-5 w-5 animate-spin rounded-full border-2 border-slate-300 border-t-brand-600" aria-hidden />
      {label}
    </div>
  );
}
