import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/api';
import type { AllocationResponse, BedResponse, BedStatus, BuildingResponse, FloorResponse, Page, RoomRequest, RoomResponse } from '@/api/types';

/** All rooms of a property (a property has tens of rooms; the API caps pages at 100, so we page through). */
export function usePropertyRooms(propertyId: number | undefined) {
  return useQuery({
    queryKey: ['rooms', 'by-property', propertyId],
    enabled: propertyId != null,
    queryFn: async ({ signal }) => {
      const all: RoomResponse[] = [];
      for (let page = 0; page < 20; page++) {
        const res = await api.get<Page<RoomResponse>>('/api/v1/rooms', { propertyId, page, size: 100, sort: 'roomNumber,asc' }, signal);
        all.push(...res.content);
        if (res.last) break;
      }
      return all;
    },
  });
}

export function useBuildings(propertyId: number | undefined) {
  return useQuery({
    queryKey: ['properties', propertyId, 'buildings'],
    enabled: propertyId != null,
    queryFn: ({ signal }) => api.get<BuildingResponse[]>(`/api/v1/properties/${propertyId}/buildings`, undefined, signal),
    staleTime: 5 * 60_000,
  });
}

export function useFloors(propertyId: number | undefined, buildingId: number | undefined) {
  return useQuery({
    queryKey: ['properties', propertyId, 'buildings', buildingId, 'floors'],
    enabled: propertyId != null && buildingId != null,
    queryFn: ({ signal }) => api.get<FloorResponse[]>(`/api/v1/properties/${propertyId}/buildings/${buildingId}/floors`, undefined, signal),
    staleTime: 5 * 60_000,
  });
}

export function useBed(bedId: number | undefined) {
  return useQuery({
    queryKey: ['beds', bedId],
    enabled: bedId != null,
    queryFn: ({ signal }) => api.get<BedResponse>(`/api/v1/beds/${bedId}`, undefined, signal),
    staleTime: 60_000,
  });
}

export function useBedAllocations(bedId: number | undefined) {
  return useQuery({
    queryKey: ['beds', bedId, 'allocations'],
    enabled: bedId != null,
    queryFn: ({ signal }) => api.get<AllocationResponse[]>(`/api/v1/beds/${bedId}/allocations`, undefined, signal),
  });
}

export function useAvailableBeds(params: { propertyId?: number; roomType?: string; maxRent?: number; enabled?: boolean }) {
  return useQuery({
    queryKey: ['beds', 'available', params],
    enabled: params.enabled !== false && params.propertyId != null,
    queryFn: ({ signal }) =>
      api.get<Page<BedResponse>>('/api/v1/beds', { propertyId: params.propertyId, status: 'AVAILABLE', roomType: params.roomType, maxRent: params.maxRent, size: 100, sort: 'bedCode,asc' }, signal),
    select: (p) => [...p.content].sort((a, b) => a.roomNumber.localeCompare(b.roomNumber, undefined, { numeric: true }) || a.bedCode.localeCompare(b.bedCode)),
  });
}

function useInvalidateRooms() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: ['rooms'] });
    void qc.invalidateQueries({ queryKey: ['beds'] });
    void qc.invalidateQueries({ queryKey: ['properties'] });
    void qc.invalidateQueries({ queryKey: ['dashboard'] });
  };
}

export function useCreateRoom(propertyId: number) {
  const inv = useInvalidateRooms();
  return useMutation({
    mutationFn: (body: RoomRequest) => api.request<RoomResponse>('/api/v1/rooms', { method: 'POST', query: { propertyId }, body }).then((r) => r.data),
    onSuccess: inv,
  });
}

export function useUpdateRoom(id: number) {
  const inv = useInvalidateRooms();
  return useMutation({
    mutationFn: (body: RoomRequest) => api.put<RoomResponse>(`/api/v1/rooms/${id}`, body),
    onSuccess: inv,
  });
}

export function useRoomStatus() {
  const inv = useInvalidateRooms();
  return useMutation({
    mutationFn: ({ id, status }: { id: number; status: 'ACTIVE' | 'INACTIVE' }) => api.patch<RoomResponse>(`/api/v1/rooms/${id}/status`, { status }),
    onSuccess: inv,
  });
}

export function useBedStatus() {
  const inv = useInvalidateRooms();
  return useMutation({
    mutationFn: ({ id, status }: { id: number; status: BedStatus }) => api.patch<BedResponse>(`/api/v1/beds/${id}/status`, { status }),
    onSuccess: inv,
  });
}

export function useCreateBuilding(propertyId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { code: string; name: string }) => api.post<BuildingResponse>(`/api/v1/properties/${propertyId}/buildings`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['properties', propertyId, 'buildings'] }),
  });
}

export function useCreateFloor(propertyId: number, buildingId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { floorNumber: number; label?: string }) => api.post<FloorResponse>(`/api/v1/properties/${propertyId}/buildings/${buildingId}/floors`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['properties', propertyId, 'buildings'] }),
  });
}
