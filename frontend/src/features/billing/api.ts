import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/api';
import type { InvoiceResponse, LineItemType, Page, PaymentResponse, RecordPaymentRequest, RefundResponse } from '@/api/types';

export interface InvoiceFilters {
  page?: number;
  size?: number;
  propertyId?: number;
  tenantId?: number;
  status?: string;
  type?: string;
  billingMonth?: string;
  dueBefore?: string;
  sort?: string;
}

export function useInvoices(f: InvoiceFilters, enabled = true) {
  return useQuery({
    queryKey: ['invoices', 'list', f],
    enabled,
    queryFn: ({ signal }) => api.get<Page<InvoiceResponse>>('/api/v1/invoices', { ...f, size: f.size ?? 20, sort: f.sort ?? 'dueDate,desc' }, signal),
    placeholderData: (p) => p,
  });
}

export function useInvoice(id: number | undefined) {
  return useQuery({
    queryKey: ['invoices', id],
    enabled: id != null && !Number.isNaN(id),
    queryFn: ({ signal }) => api.get<InvoiceResponse>(`/api/v1/invoices/${id}`, undefined, signal),
  });
}

export interface PaymentFilters {
  page?: number;
  size?: number;
  propertyId?: number;
  tenantId?: number;
  invoiceId?: number;
  method?: string;
  from?: string;
  to?: string;
}

export function usePayments(f: PaymentFilters, enabled = true) {
  return useQuery({
    queryKey: ['payments', 'list', f],
    enabled,
    queryFn: ({ signal }) => api.get<Page<PaymentResponse>>('/api/v1/payments', { ...f, size: f.size ?? 20, sort: 'paidAt,desc' }, signal),
    placeholderData: (p) => p,
  });
}

export function usePayment(id: number) {
  return useQuery({ queryKey: ['payments', id], queryFn: ({ signal }) => api.get<PaymentResponse>(`/api/v1/payments/${id}`, undefined, signal) });
}

export function useRefunds(paymentId: number) {
  return useQuery({ queryKey: ['payments', paymentId, 'refunds'], queryFn: ({ signal }) => api.get<RefundResponse[]>(`/api/v1/payments/${paymentId}/refunds`, undefined, signal) });
}

function useInvalidateBilling() {
  const qc = useQueryClient();
  return () => {
    for (const k of ['invoices', 'payments', 'tenants', 'dashboard']) void qc.invalidateQueries({ queryKey: [k] });
  };
}

export function useInvoiceActions(id: number) {
  const inv = useInvalidateBilling();
  return {
    charge: useMutation({ mutationFn: (b: { type: LineItemType; description: string; amount: number }) => api.post<InvoiceResponse>(`/api/v1/invoices/${id}/charges`, b), onSuccess: inv }),
    discount: useMutation({ mutationFn: (b: { reason: string; amount: number }) => api.post<InvoiceResponse>(`/api/v1/invoices/${id}/discounts`, b), onSuccess: inv }),
    void: useMutation({ mutationFn: (reason: string) => api.post<InvoiceResponse>(`/api/v1/invoices/${id}/void`, { reason }), onSuccess: inv }),
  };
}

export interface IdempotentResult<T> {
  data: T;
  /** true when the server answered with `Idempotent-Replayed: true` (the original result of an earlier attempt). */
  replayed: boolean;
  status: number;
}

/** POST /payments with an Idempotency-Key. The caller owns the key (see IdempotentSubmission). */
export async function recordPayment(body: RecordPaymentRequest, idempotencyKey: string): Promise<IdempotentResult<PaymentResponse>> {
  const r = await api.request<PaymentResponse>('/api/v1/payments', { method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } });
  return { data: r.data, status: r.status, replayed: r.headers.get('Idempotent-Replayed') === 'true' };
}

export async function recordRefund(paymentId: number, body: { amount: number; reason: string }, idempotencyKey: string): Promise<IdempotentResult<RefundResponse>> {
  const r = await api.request<RefundResponse>(`/api/v1/payments/${paymentId}/refunds`, { method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } });
  return { data: r.data, status: r.status, replayed: r.headers.get('Idempotent-Replayed') === 'true' };
}

export function useRecordPayment() {
  const inv = useInvalidateBilling();
  return useMutation({ mutationFn: ({ body, key }: { body: RecordPaymentRequest; key: string }) => recordPayment(body, key), onSuccess: inv });
}

export function useRecordRefund(paymentId: number) {
  const inv = useInvalidateBilling();
  return useMutation({ mutationFn: ({ body, key }: { body: { amount: number; reason: string }; key: string }) => recordRefund(paymentId, body, key), onSuccess: inv });
}

/** Fetch the receipt PDF with the bearer token and return it as a Blob. */
export async function fetchReceipt(paymentId: number): Promise<Blob> {
  const r = await api.blob(`/api/v1/payments/${paymentId}/receipt`);
  return r.data;
}
