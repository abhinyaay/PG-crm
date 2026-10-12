import { useQuery } from '@tanstack/react-query';
import { api } from '@/api';
import type { OccupancyReport, OperationsDashboard, PaymentsReport, PropertiesReport, RevenueReport, StaffDashboard, TenantDashboard } from '@/api/types';

/** GET /api/v1/dashboard returns a role-specific shape. */
export function useDashboard<T extends OperationsDashboard | StaffDashboard | TenantDashboard>(propertyId?: number) {
  return useQuery({
    queryKey: ['dashboard', propertyId ?? 'all'],
    queryFn: ({ signal }) => api.get<T>('/api/v1/dashboard', { propertyId }, signal),
    refetchInterval: 60_000,
  });
}

export interface AnalyticsParams {
  propertyId?: number;
  from?: string;
  to?: string;
}

function analyticsQuery<T>(path: string, params: AnalyticsParams & Record<string, string | number | undefined>) {
  return {
    queryKey: ['analytics', path, params],
    queryFn: ({ signal }: { signal: AbortSignal }) => api.get<T>(`/analytics/${path}`, params, signal),
    staleTime: 5 * 60_000,
    retry: 1,
    refetchOnWindowFocus: false,
  };
}

export const useOccupancyReport = (p: AnalyticsParams) => useQuery(analyticsQuery<OccupancyReport>('occupancy', { ...p }));
export const useRevenueReport = (p: AnalyticsParams) => useQuery(analyticsQuery<RevenueReport>('revenue', { ...p }));
export const usePaymentsReport = (p: AnalyticsParams) => useQuery(analyticsQuery<PaymentsReport>('payments', { ...p }));
export const usePropertiesReport = (p: AnalyticsParams & { sortBy?: string; order?: 'asc' | 'desc' }) =>
  useQuery(analyticsQuery<PropertiesReport>('properties', { ...p }));
