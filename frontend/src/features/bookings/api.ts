import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/api';
import type { AllocationResponse, BookingResponse, CheckInResponse, CheckOutResponse, Deduction, Page, TransferResponse } from '@/api/types';

export function useBookings(f: { propertyId?: number; tenantId?: number; status?: string; page?: number }) {
  return useQuery({
    queryKey: ['bookings', 'list', f],
    queryFn: ({ signal }) => api.get<Page<BookingResponse>>('/api/v1/bookings', { ...f, size: 20 }, signal),
    placeholderData: (p) => p,
  });
}

export function useAllocations(f: { propertyId?: number; tenantId?: number; bedId?: number; status?: string; page?: number; expectedEndBefore?: string }) {
  return useQuery({
    queryKey: ['allocations', 'list', f],
    queryFn: ({ signal }) => api.get<Page<AllocationResponse>>('/api/v1/allocations', { ...f, size: 20 }, signal),
    placeholderData: (p) => p,
  });
}

export function useTenantAllocations(tenantId: number | undefined) {
  return useQuery({
    queryKey: ['tenants', tenantId, 'allocations'],
    enabled: tenantId != null,
    queryFn: ({ signal }) => api.get<AllocationResponse[]>(`/api/v1/tenants/${tenantId}/allocations`, undefined, signal),
  });
}

/** Everything that changes when occupancy changes. */
function useInvalidateOccupancy() {
  const qc = useQueryClient();
  return () => {
    for (const key of ['bookings', 'allocations', 'beds', 'rooms', 'tenants', 'invoices', 'dashboard', 'properties']) {
      void qc.invalidateQueries({ queryKey: [key] });
    }
  };
}

export function useReserveBed() {
  const inv = useInvalidateOccupancy();
  return useMutation({
    mutationFn: (b: { tenantId: number; bedId: number; expectedCheckInDate: string; holdHours?: number; notes?: string }) => api.post<BookingResponse>('/api/v1/bookings', b),
    onSuccess: inv,
  });
}

export function useCancelBooking() {
  const inv = useInvalidateOccupancy();
  return useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) => api.post<BookingResponse>(`/api/v1/bookings/${id}/cancel`, { reason }),
    onSuccess: inv,
  });
}

export function useCheckIn() {
  const inv = useInvalidateOccupancy();
  return useMutation({
    mutationFn: (b: { tenantId: number; bookingId?: number; bedId?: number; checkInDate: string; monthlyRent?: number; securityDeposit?: number }) =>
      api.post<CheckInResponse>('/api/v1/allocations/check-in', b),
    onSuccess: inv,
  });
}

export function useCheckOut() {
  const inv = useInvalidateOccupancy();
  return useMutation({
    mutationFn: ({ id, ...b }: { id: number; checkOutDate: string; deductions: Deduction[]; sendBedToMaintenance: boolean }) =>
      api.post<CheckOutResponse>(`/api/v1/allocations/${id}/check-out`, b),
    onSuccess: inv,
  });
}

export function useNotice() {
  const inv = useInvalidateOccupancy();
  return {
    give: useMutation({
      mutationFn: ({ id, expectedCheckOutDate }: { id: number; expectedCheckOutDate: string }) => api.post<AllocationResponse>(`/api/v1/allocations/${id}/notice`, { expectedCheckOutDate }),
      onSuccess: inv,
    }),
    withdraw: useMutation({ mutationFn: (id: number) => api.post<AllocationResponse>(`/api/v1/allocations/${id}/notice/withdraw`), onSuccess: inv }),
  };
}

export function useTransfer() {
  const inv = useInvalidateOccupancy();
  return useMutation({
    mutationFn: ({ id, ...b }: { id: number; targetBedId: number; transferDate: string; newMonthlyRent?: number }) => api.post<TransferResponse>(`/api/v1/allocations/${id}/transfer`, b),
    onSuccess: inv,
  });
}
