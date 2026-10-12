import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import type { ErrorEnvelope, FieldError } from './types';

/** Normalised API error (backend + analytics share the envelope `{status, code, message, fieldErrors, correlationId}`). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId?: string;
  readonly fieldErrors: FieldError[];
  readonly path?: string;

  constructor(init: { status: number; code: string; message: string; correlationId?: string; fieldErrors?: FieldError[]; path?: string }) {
    super(init.message);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.correlationId = init.correlationId;
    this.fieldErrors = init.fieldErrors ?? [];
    this.path = init.path;
  }

  get isNetworkError(): boolean {
    return this.status === 0;
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

const STATUS_FALLBACK: Record<number, string> = {
  400: 'The request was not valid.',
  401: 'You need to sign in again.',
  403: 'You do not have permission to do that.',
  404: 'Not found.',
  409: 'This record was changed by someone else. Reload and try again.',
  422: 'The request could not be processed.',
  429: 'Too many requests. Please wait a moment and try again.',
  500: 'Something went wrong on the server.',
  502: 'The service is temporarily unavailable.',
  503: 'The service is temporarily unavailable.',
  504: 'The service took too long to respond.',
};

function isEnvelope(v: unknown): v is Partial<ErrorEnvelope> {
  return typeof v === 'object' && v !== null && ('code' in v || 'message' in v);
}

/** Build an ApiError from a non-2xx Response, tolerating non-JSON bodies (e.g. nginx HTML error pages). */
export async function parseErrorResponse(response: Response): Promise<ApiError> {
  const headerCorrelation = response.headers.get('X-Correlation-Id') ?? undefined;
  let body: unknown = null;
  try {
    const text = await response.text();
    body = text ? JSON.parse(text) : null;
  } catch {
    body = null;
  }
  if (isEnvelope(body)) {
    return new ApiError({
      status: typeof body.status === 'number' ? body.status : response.status,
      code: body.code ?? `HTTP_${response.status}`,
      message: body.message || STATUS_FALLBACK[response.status] || `Request failed (${response.status})`,
      correlationId: body.correlationId ?? headerCorrelation,
      fieldErrors: Array.isArray(body.fieldErrors) ? body.fieldErrors : [],
      path: body.path,
    });
  }
  return new ApiError({
    status: response.status,
    code: `HTTP_${response.status}`,
    message: STATUS_FALLBACK[response.status] ?? `Request failed (${response.status})`,
    correlationId: headerCorrelation,
  });
}

/** Human readable message for any thrown value. */
export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return 'Unexpected error';
}

/**
 * Convert a backend field path ("address.city", "emergencyContacts[0].phone", "contacts[1].name")
 * to a react-hook-form path ("address.city", "emergencyContacts.0.phone").
 */
export function toFormPath(field: string): string {
  return field.replace(/\[(\d+)\]/g, '.$1');
}

/**
 * Map server-side field errors onto a react-hook-form instance. Returns the field errors that could NOT be mapped
 * (unknown fields) so callers can show them in a toast instead of losing them.
 */
export function applyFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  knownFields?: readonly string[],
  aliases: Record<string, string> = {},
): FieldError[] {
  if (!(error instanceof ApiError) || error.fieldErrors.length === 0) return [];
  const unmapped: FieldError[] = [];
  let first = true;
  for (const fe of error.fieldErrors) {
    const path = aliases[fe.field] ?? toFormPath(fe.field);
    const root = path.split('.')[0] ?? path;
    if (knownFields && !knownFields.includes(root) && !knownFields.includes(path)) {
      unmapped.push(fe);
      continue;
    }
    setError(path as Path<T>, { type: 'server', message: fe.message }, { shouldFocus: first });
    first = false;
  }
  return unmapped;
}
