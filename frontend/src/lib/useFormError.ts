import { useCallback } from 'react';
import type { FieldValues, UseFormSetError } from 'react-hook-form';
import { ApiError, applyFieldErrors } from '@/api/errors';
import { useToast } from '@/components/ui/Toast';

/**
 * Standard handling of a failed form submission:
 * - server fieldErrors are mapped onto the matching form fields (first one focused);
 * - a toast shows the error message and correlation id (field errors that could not be mapped are listed in it).
 */
export function useFormError() {
  const toast = useToast();
  return useCallback(
    <T extends FieldValues>(error: unknown, setError: UseFormSetError<T>, fields: readonly string[], title = 'Could not save', aliases?: Record<string, string>) => {
      const unmapped = applyFieldErrors(error, setError, fields, aliases);
      if (error instanceof ApiError && error.fieldErrors.length > 0 && unmapped.length === 0) {
        toast.error(title, 'Please correct the highlighted fields.', error.correlationId);
        return;
      }
      if (error instanceof ApiError && unmapped.length > 0) {
        toast.error(title, `${error.message}: ${unmapped.map((f) => `${f.field} ${f.message}`).join('; ')}`, error.correlationId);
        return;
      }
      toast.apiError(error, title);
    },
    [toast],
  );
}
