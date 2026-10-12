import { describe, expect, it } from 'vitest';
import {
  addMonths,
  formatDate,
  formatDateTime,
  formatMoney,
  formatMoneyCompact,
  formatMonth,
  formatPercent,
  formatRelative,
  humanize,
  isoToKolkataLocal,
  kolkataLocalToIso,
  methodLabel,
  todayInBusinessTz,
} from './format';

describe('money', () => {
  it('formats INR with Indian digit grouping', () => {
    expect(formatMoney(1234567.5)).toBe('₹12,34,567.5');
    expect(formatMoney(12500)).toBe('₹12,500');
    expect(formatMoney(0)).toBe('₹0');
    expect(formatMoney('852572.59')).toBe('₹8,52,572.59');
    expect(formatMoney(null)).toBe('—');
  });

  it('compact formatting uses lakh / crore', () => {
    expect(formatMoneyCompact(612866.67)).toBe('₹6.1L');
    expect(formatMoneyCompact(10640064.69)).toBe('₹1.1Cr');
  });
});

describe('dates in Asia/Kolkata', () => {
  it('formats business dates without a timezone shift', () => {
    expect(formatDate('2026-10-05')).toBe('05 Oct 2026');
    expect(formatDate('2026-01-01')).toBe('01 Jan 2026');
  });

  it('converts instants to IST (UTC 22:30 is 04:00 the next day in Kolkata)', () => {
    expect(formatDate('2026-10-05T22:30:00Z')).toBe('06 Oct 2026');
    expect(formatDateTime('2026-10-05T22:30:00Z')).toMatch(/06 Oct 2026, 04:00\s?am/i);
  });

  it("computes today's date in Kolkata", () => {
    expect(todayInBusinessTz(new Date('2026-10-12T20:00:00Z'))).toBe('2026-10-13');
    expect(todayInBusinessTz(new Date('2026-10-12T18:00:00Z'))).toBe('2026-10-12');
  });

  it('round-trips datetime-local values through IST', () => {
    expect(kolkataLocalToIso('2026-10-13T09:30')).toBe('2026-10-13T04:00:00.000Z');
    expect(isoToKolkataLocal('2026-10-13T04:00:00.000Z')).toBe('2026-10-13T09:30');
  });

  it('formats months and shifts them', () => {
    expect(formatMonth('2026-10')).toBe('Oct 2026');
    expect(formatMonth('2026-10-01')).toBe('Oct 2026');
    expect(addMonths('2026-10', -11)).toBe('2025-11');
    expect(addMonths('2026-12', 1)).toBe('2027-01');
  });

  it('relative times', () => {
    const now = new Date('2026-10-13T00:00:00Z');
    expect(formatRelative('2026-10-12T21:00:00Z', now)).toBe('3h ago');
    expect(formatRelative('2026-10-15T00:00:00Z', now)).toBe('in 2d');
  });
});

describe('labels', () => {
  it('humanizes enum values and formats percents', () => {
    expect(humanize('PARTIALLY_PAID')).toBe('Partially paid');
    expect(methodLabel('UPI')).toBe('UPI');
    expect(methodLabel('BANK_TRANSFER')).toBe('Bank transfer');
    expect(formatPercent(81.818)).toBe('81.8%');
    expect(formatPercent(null)).toBe('—');
  });
});
