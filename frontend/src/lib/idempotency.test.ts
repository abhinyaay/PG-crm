import { afterEach, describe, expect, it, vi } from 'vitest';
import { session } from '@/api';
import { recordPayment } from '@/features/billing/api';
import type { RecordPaymentRequest } from '@/api/types';
import { jsonResponse, tokens } from '@/test/utils';
import { IdempotentSubmission, newIdempotencyKey, stableStringify } from './idempotency';

const body: RecordPaymentRequest = { invoiceId: 9, amount: 5000, method: 'UPI', externalReference: 'UPI-1' };

describe('IdempotentSubmission', () => {
  it('reuses the key for retries of the same submission (key order does not matter)', () => {
    let n = 0;
    const s = new IdempotentSubmission<RecordPaymentRequest>(() => `k${++n}`);
    const first = s.keyFor(body);
    expect(s.keyFor({ externalReference: 'UPI-1', method: 'UPI', amount: 5000, invoiceId: 9 })).toBe(first);
    expect(first).toBe('k1');
  });

  it('issues a new key when the request changes, and after reset()', () => {
    let n = 0;
    const s = new IdempotentSubmission<RecordPaymentRequest>(() => `k${++n}`);
    const a = s.keyFor(body);
    const b = s.keyFor({ ...body, amount: 4999 });
    expect(b).not.toBe(a);
    s.reset();
    expect(s.keyFor({ ...body, amount: 4999 })).not.toBe(b);
  });

  it('generates RFC 4122 v4 UUIDs', () => {
    expect(newIdempotencyKey()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  it('stableStringify ignores undefined fields and key order', () => {
    expect(stableStringify({ b: 1, a: undefined, c: [1, { y: 2, x: 1 }] })).toBe('{"b":1,"c":[1,{"x":1,"y":2}]}');
  });
});

describe('recordPayment with retries', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    session.clear('logout', false);
  });

  it('sends the same Idempotency-Key on a retry after a network failure and reports the replay', async () => {
    session.establish(tokens(1));
    const sentKeys: string[] = [];
    let call = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
        sentKeys.push((init?.headers as Record<string, string>)['Idempotency-Key']!);
        call += 1;
        if (call === 1) throw new TypeError('Failed to fetch'); // response lost; the server may have applied it
        return jsonResponse({ id: 77, receiptNumber: 'RCPT-77', amount: 5000 }, 200, { 'Idempotent-Replayed': 'true' });
      }),
    );
    const submission = new IdempotentSubmission<RecordPaymentRequest>();

    await expect(recordPayment(body, submission.keyFor(body))).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
    const retry = await recordPayment(body, submission.keyFor(body));

    expect(sentKeys).toHaveLength(2);
    expect(sentKeys[0]).toBe(sentKeys[1]);
    expect(retry).toMatchObject({ replayed: true, status: 200, data: { id: 77 } });
  });

  it('reports a first-time payment (201) as not replayed', async () => {
    session.establish(tokens(1));
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ id: 78, receiptNumber: 'RCPT-78', amount: 10 }, 201)));
    const r = await recordPayment({ ...body, amount: 10 }, 'key-1');
    expect(r).toMatchObject({ replayed: false, status: 201 });
  });
});
