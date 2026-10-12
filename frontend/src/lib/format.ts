/** Formatting helpers: money in INR (en-IN grouping), dates/times in Asia/Kolkata. */
export const BUSINESS_TZ = 'Asia/Kolkata';
const LOCALE = 'en-IN';

const inr = new Intl.NumberFormat(LOCALE, { style: 'currency', currency: 'INR', maximumFractionDigits: 2, minimumFractionDigits: 0 });
const inrCompact = new Intl.NumberFormat(LOCALE, { style: 'currency', currency: 'INR', notation: 'compact', maximumFractionDigits: 1 });
const num = new Intl.NumberFormat(LOCALE);

const isNil = (v: unknown): v is null | undefined => v === null || v === undefined || (typeof v === 'number' && Number.isNaN(v));

/** ₹1,23,456.5 → "₹1,23,456.5"; paise are shown only when present. */
export function formatMoney(value: number | string | null | undefined): string {
  if (isNil(value) || value === '') return '—';
  const n = typeof value === 'string' ? Number(value) : value;
  if (Number.isNaN(n)) return '—';
  return inr.format(n);
}

/** Compact INR for charts/KPIs: ₹6.1L, ₹1.1Cr (en-IN compact notation uses lakh/crore). */
export function formatMoneyCompact(value: number | null | undefined): string {
  if (isNil(value)) return '—';
  return inrCompact.format(value);
}

export function formatNumber(value: number | null | undefined): string {
  if (isNil(value)) return '—';
  return num.format(value);
}

export function formatPercent(value: number | null | undefined, digits = 1): string {
  if (isNil(value)) return '—';
  return `${value.toFixed(digits)}%`;
}

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * Business dates ("2026-10-05") are calendar dates, not instants: they are formatted as-is (no timezone shift).
 * Instants ("2026-10-05T22:30:00Z") are converted to Asia/Kolkata.
 */
export function formatDate(value: string | null | undefined): string {
  if (!value) return '—';
  const m = DATE_ONLY.exec(value);
  if (m) {
    const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
    return new Intl.DateTimeFormat(LOCALE, { day: '2-digit', month: 'short', year: 'numeric', timeZone: 'UTC' }).format(d);
  }
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return new Intl.DateTimeFormat(LOCALE, { day: '2-digit', month: 'short', year: 'numeric', timeZone: BUSINESS_TZ }).format(d);
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return new Intl.DateTimeFormat(LOCALE, {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: true,
    timeZone: BUSINESS_TZ,
  }).format(d);
}

/** "2026-10" or "2026-10-01" → "Oct 2026" */
export function formatMonth(value: string | null | undefined): string {
  if (!value) return '—';
  const m = /^(\d{4})-(\d{2})/.exec(value);
  if (!m) return value;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, 1));
  return new Intl.DateTimeFormat(LOCALE, { month: 'short', year: 'numeric', timeZone: 'UTC' }).format(d);
}

/** Short month label for chart axes: "2026-10" → "Oct '26" */
export function formatMonthShort(value: string): string {
  const m = /^(\d{4})-(\d{2})/.exec(value);
  if (!m) return value;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, 1));
  const mon = new Intl.DateTimeFormat(LOCALE, { month: 'short', timeZone: 'UTC' }).format(d);
  return `${mon} '${m[1]!.slice(2)}`;
}

/** Today's calendar date in Asia/Kolkata as "YYYY-MM-DD" (for date inputs / business dates). */
export function todayInBusinessTz(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: BUSINESS_TZ, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now);
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? '';
  return `${get('year')}-${get('month')}-${get('day')}`;
}

/** Current month in Asia/Kolkata as "YYYY-MM". */
export function currentMonthInBusinessTz(now: Date = new Date()): string {
  return todayInBusinessTz(now).slice(0, 7);
}

/** Shift a "YYYY-MM" month by n months. */
export function addMonths(month: string, n: number): string {
  const [y, m] = month.split('-').map(Number) as [number, number];
  const d = new Date(Date.UTC(y, m - 1 + n, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}

/** Relative time ("3h ago", "in 2d") — used for SLA badges and notifications. */
export function formatRelative(value: string | null | undefined, now: Date = new Date()): string {
  if (!value) return '—';
  const t = new Date(value).getTime();
  if (Number.isNaN(t)) return value;
  const diff = t - now.getTime();
  const abs = Math.abs(diff);
  const min = 60_000;
  const hr = 60 * min;
  const day = 24 * hr;
  let text: string;
  if (abs < min) text = 'just now';
  else if (abs < hr) text = `${Math.round(abs / min)}m`;
  else if (abs < day) text = `${Math.round(abs / hr)}h`;
  else text = `${Math.round(abs / day)}d`;
  if (text === 'just now') return text;
  return diff < 0 ? `${text} ago` : `in ${text}`;
}

/** "PARTIALLY_PAID" → "Partially paid" */
export function humanize(value: string | null | undefined): string {
  if (!value) return '—';
  const s = value.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Convert a local "YYYY-MM-DDTHH:mm" (input type=datetime-local, interpreted in Asia/Kolkata) to an ISO instant. */
export function kolkataLocalToIso(local: string): string {
  // Asia/Kolkata has a fixed +05:30 offset (no DST).
  const withSeconds = local.length === 16 ? `${local}:00` : local;
  return new Date(`${withSeconds}+05:30`).toISOString();
}

/** ISO instant → "YYYY-MM-DDTHH:mm" in Asia/Kolkata (for datetime-local inputs). */
export function isoToKolkataLocal(iso: string | Date = new Date()): string {
  const d = typeof iso === 'string' ? new Date(iso) : iso;
  const shifted = new Date(d.getTime() + 330 * 60_000);
  return shifted.toISOString().slice(0, 16);
}

const METHOD_LABELS: Record<string, string> = { CASH: 'Cash', UPI: 'UPI', CARD: 'Card', BANK_TRANSFER: 'Bank transfer', CHEQUE: 'Cheque' };
/** Payment method label ("UPI", not "Upi"). */
export function methodLabel(method: string | null | undefined): string {
  if (!method) return '—';
  return METHOD_LABELS[method] ?? humanize(method);
}
