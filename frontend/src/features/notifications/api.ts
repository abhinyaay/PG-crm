import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/api';
import type { NotificationResponse, Page } from '@/api/types';

export const UNREAD_POLL_MS = 30_000;

export function useUnreadCount(enabled = true) {
  return useQuery({
    queryKey: ['notifications', 'unread-count'],
    queryFn: ({ signal }) => api.get<{ unread: number }>('/api/v1/notifications/unread-count', undefined, signal),
    select: (d) => d.unread,
    refetchInterval: UNREAD_POLL_MS,
    refetchIntervalInBackground: false,
    enabled,
  });
}

export function useNotifications(params: { page: number; unreadOnly: boolean; size?: number }) {
  return useQuery({
    queryKey: ['notifications', 'list', params],
    queryFn: ({ signal }) =>
      api.get<Page<NotificationResponse>>('/api/v1/notifications', { page: params.page, size: params.size ?? 20, unreadOnly: params.unreadOnly || undefined }, signal),
    placeholderData: (prev) => prev,
  });
}

export function useMarkRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.post<NotificationResponse>(`/api/v1/notifications/${id}/read`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['notifications'] }),
  });
}

export function useMarkAllRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<Record<string, unknown>>('/api/v1/notifications/read-all'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['notifications'] }),
  });
}

/** Deep link for a notification's reference. */
export function notificationLink(n: NotificationResponse, isTenant: boolean): string | null {
  if (!n.referenceId || !n.referenceType) return null;
  switch (n.referenceType.toUpperCase()) {
    case 'MAINTENANCE':
    case 'MAINTENANCE_REQUEST':
      return `/maintenance/${n.referenceId}`;
    case 'COMPLAINT':
      return `/complaints/${n.referenceId}`;
    case 'INVOICE':
      return isTenant ? `/me/bills?invoice=${n.referenceId}` : `/invoices/${n.referenceId}`;
    case 'PAYMENT':
      return isTenant ? '/me/bills' : `/payments/${n.referenceId}`;
    case 'BOOKING':
      return isTenant ? '/me' : '/bookings';
    case 'ALLOCATION':
      return isTenant ? '/me' : null;
    case 'REFUND':
      return isTenant ? '/me/bills' : '/payments';
    case 'TENANT':
      return isTenant ? '/me/profile' : `/tenants/${n.referenceId}`;
    default:
      return null;
  }
}
