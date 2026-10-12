import { forwardRef, useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

export const controlClasses =
  'block w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 text-base text-slate-900 shadow-sm ' +
  'placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-500/40 ' +
  'disabled:cursor-not-allowed disabled:bg-slate-100 md:text-sm min-h-touch ' +
  'dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:placeholder:text-slate-500 dark:disabled:bg-slate-800 ' +
  'aria-[invalid=true]:border-red-500 aria-[invalid=true]:ring-red-500/30';

interface FieldShellProps {
  id: string;
  label?: ReactNode;
  error?: string;
  hint?: ReactNode;
  required?: boolean;
  className?: string;
  children: ReactNode;
}

export function FieldShell({ id, label, error, hint, required, className, children }: FieldShellProps) {
  return (
    <div className={cn('space-y-1.5', className)}>
      {label && (
        <label htmlFor={id} className="block text-sm font-medium text-slate-700 dark:text-slate-300">
          {label}
          {required && (
            <span className="ml-0.5 text-red-600" aria-hidden>
              *
            </span>
          )}
        </label>
      )}
      {children}
      {error ? (
        <p id={`${id}-error`} className="text-sm text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-hint`} className="text-xs text-slate-500 dark:text-slate-400">
          {hint}
        </p>
      ) : null}
    </div>
  );
}

type Common = { label?: ReactNode; error?: string; hint?: ReactNode; containerClassName?: string };

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement> & Common>(function Input(
  { label, error, hint, containerClassName, className, id, required, ...rest },
  ref,
) {
  const auto = useId();
  const fid = id ?? auto;
  return (
    <FieldShell id={fid} label={label} error={error} hint={hint} required={required} className={containerClassName}>
      <input
        ref={ref}
        id={fid}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${fid}-error` : hint ? `${fid}-hint` : undefined}
        aria-required={required || undefined}
        className={cn(controlClasses, className)}
        {...rest}
      />
    </FieldShell>
  );
});

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement> & Common>(function Select(
  { label, error, hint, containerClassName, className, id, required, children, ...rest },
  ref,
) {
  const auto = useId();
  const fid = id ?? auto;
  return (
    <FieldShell id={fid} label={label} error={error} hint={hint} required={required} className={containerClassName}>
      <select
        ref={ref}
        id={fid}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${fid}-error` : hint ? `${fid}-hint` : undefined}
        aria-required={required || undefined}
        className={cn(controlClasses, 'pr-8', className)}
        {...rest}
      >
        {children}
      </select>
    </FieldShell>
  );
});

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement> & Common>(function Textarea(
  { label, error, hint, containerClassName, className, id, required, rows = 3, ...rest },
  ref,
) {
  const auto = useId();
  const fid = id ?? auto;
  return (
    <FieldShell id={fid} label={label} error={error} hint={hint} required={required} className={containerClassName}>
      <textarea
        ref={ref}
        id={fid}
        rows={rows}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${fid}-error` : hint ? `${fid}-hint` : undefined}
        aria-required={required || undefined}
        className={cn(controlClasses, className)}
        {...rest}
      />
    </FieldShell>
  );
});

export const Checkbox = forwardRef<HTMLInputElement, Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> & { label: ReactNode; description?: ReactNode }>(
  function Checkbox({ label, description, className, id, ...rest }, ref) {
    const auto = useId();
    const fid = id ?? auto;
    return (
      <label htmlFor={fid} className={cn('flex min-h-touch cursor-pointer items-start gap-3 py-2', className)}>
        <input
          ref={ref}
          id={fid}
          type="checkbox"
          className="mt-0.5 h-5 w-5 shrink-0 rounded border-slate-300 text-brand-600 accent-brand-600 focus:ring-2 focus:ring-brand-500 dark:border-slate-600"
          {...rest}
        />
        <span className="text-sm">
          <span className="font-medium text-slate-800 dark:text-slate-200">{label}</span>
          {description && <span className="block text-slate-500 dark:text-slate-400">{description}</span>}
        </span>
      </label>
    );
  },
);
