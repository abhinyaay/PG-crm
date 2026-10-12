import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { CheckCircle2, Copy, Info, X, XCircle } from 'lucide-react';
import { ApiError } from '@/api/errors';
import { cn } from '@/lib/cn';

type ToastKind = 'success' | 'error' | 'info';
interface ToastItem {
  id: number;
  kind: ToastKind;
  title: string;
  message?: string;
  correlationId?: string;
}

interface ToastApi {
  success: (title: string, message?: string) => void;
  info: (title: string, message?: string) => void;
  error: (title: string, message?: string, correlationId?: string) => void;
  /** Show an API error: message + correlation id (for support). */
  apiError: (error: unknown, title?: string) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const nextId = useRef(1);

  const dismiss = useCallback((id: number) => setItems((xs) => xs.filter((t) => t.id !== id)), []);
  const push = useCallback(
    (t: Omit<ToastItem, 'id'>) => {
      const id = nextId.current++;
      setItems((xs) => [...xs.slice(-3), { ...t, id }]);
      setTimeout(() => dismiss(id), t.kind === 'error' ? 9000 : 4500);
    },
    [dismiss],
  );

  const api = useMemo<ToastApi>(
    () => ({
      success: (title, message) => push({ kind: 'success', title, message }),
      info: (title, message) => push({ kind: 'info', title, message }),
      error: (title, message, correlationId) => push({ kind: 'error', title, message, correlationId }),
      apiError: (error, title = 'Request failed') => {
        if (error instanceof ApiError) {
          const fieldHint = error.fieldErrors.length ? ` (${error.fieldErrors.map((f) => `${f.field}: ${f.message}`).join('; ')})` : '';
          push({ kind: 'error', title, message: error.message + fieldHint, correlationId: error.correlationId });
        } else {
          push({ kind: 'error', title, message: error instanceof Error ? error.message : 'Unexpected error' });
        }
      },
    }),
    [push],
  );

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div
        aria-live="polite"
        className="pointer-events-none fixed inset-x-0 top-0 z-[60] flex flex-col items-center gap-2 p-3 pt-[max(0.75rem,env(safe-area-inset-top))] sm:bottom-auto sm:items-end sm:p-4"
      >
        {items.map((t) => (
          <div
            key={t.id}
            role={t.kind === 'error' ? 'alert' : 'status'}
            className={cn(
              'pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-2xl border p-3 shadow-lg',
              'bg-white dark:bg-slate-900',
              t.kind === 'error' ? 'border-red-200 dark:border-red-900' : 'border-slate-200 dark:border-slate-700',
            )}
          >
            {t.kind === 'success' && <CheckCircle2 className="mt-0.5 h-5 w-5 shrink-0 text-emerald-600" aria-hidden />}
            {t.kind === 'error' && <XCircle className="mt-0.5 h-5 w-5 shrink-0 text-red-600" aria-hidden />}
            {t.kind === 'info' && <Info className="mt-0.5 h-5 w-5 shrink-0 text-brand-600" aria-hidden />}
            <div className="min-w-0 flex-1 text-sm">
              <p className="font-medium text-slate-900 dark:text-slate-100">{t.title}</p>
              {t.message && <p className="mt-0.5 break-words text-slate-600 dark:text-slate-300">{t.message}</p>}
              {t.correlationId && (
                <button
                  type="button"
                  className="mt-1 inline-flex items-center gap-1 break-all text-left font-mono text-xs text-slate-500 hover:text-slate-700 dark:text-slate-400"
                  onClick={() => void navigator.clipboard?.writeText(t.correlationId ?? '')}
                  title="Copy reference"
                >
                  <Copy className="h-3 w-3 shrink-0" aria-hidden /> Ref: {t.correlationId}
                </button>
              )}
            </div>
            <button
              type="button"
              onClick={() => dismiss(t.id)}
              className="-m-1 inline-flex h-8 w-8 items-center justify-center rounded-full text-slate-400 hover:bg-slate-100 hover:text-slate-600 dark:hover:bg-slate-800"
              aria-label="Dismiss notification"
            >
              <X className="h-4 w-4" aria-hidden />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used inside <ToastProvider>');
  return ctx;
}
