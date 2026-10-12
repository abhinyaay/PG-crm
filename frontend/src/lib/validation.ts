import { z } from 'zod';

/** Backend password policy: 8-72 chars, at least one letter and one digit. */
export const PASSWORD_RULE = /^(?=.*[A-Za-z])(?=.*\d).{8,72}$/;
export const PASSWORD_HINT = '8–72 characters with at least one letter and one digit';
/** Backend phone rule: optional +, 10-15 digits. */
export const PHONE_RULE = /^\+?[0-9]{10,15}$/;
export const PHONE_HINT = '10–15 digits, optional leading +';

export const optionalText = (max: number) =>
  z
    .string()
    .trim()
    .max(max, `At most ${max} characters`)
    .optional()
    .transform((v) => (v ? v : undefined));

export const optionalEmail = z
  .string()
  .trim()
  .max(255)
  .optional()
  .refine((v) => !v || z.string().email().safeParse(v).success, 'Enter a valid email address')
  .transform((v) => (v ? v : undefined));

export const phone = z.string().trim().regex(PHONE_RULE, PHONE_HINT);

/** Number input that may be empty. */
export const moneyField = (label: string, min = 0) =>
  z.coerce.number({ invalid_type_error: `${label} must be a number` }).min(min, `${label} must be at least ${min}`);

/** Strip empty strings so optional fields are omitted from request bodies. */
export function compact<T extends Record<string, unknown>>(obj: T): T {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(obj)) {
    if (v === '' || v === undefined || v === null) continue;
    out[k] = v;
  }
  return out as T;
}
