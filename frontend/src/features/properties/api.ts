import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/api';
import type { AssignmentResponse, OccupancyResponse, Page, PropertyRequest, PropertyResponse, PropertyStatus } from '@/api/types';

export function usePropertyList(f: { q?: string; status?: string; city?: string; page: number }) {
  return useQuery({
    queryKey: ['properties', 'list', f],
    queryFn: ({ signal }) => api.get<Page<PropertyResponse>>('/api/v1/properties', { ...f, size: 20, sort: 'name,asc' }, signal),
    placeholderData: (p) => p,
  });
}

export function useProperty(id: number | undefined) {
  return useQuery({
    queryKey: ['properties', id],
    enabled: id != null,
    queryFn: ({ signal }) => api.get<PropertyResponse>(`/api/v1/properties/${id}`, undefined, signal),
  });
}

export function useOccupancy(id: number | undefined) {
  return useQuery({
    queryKey: ['properties', id, 'occupancy'],
    enabled: id != null,
    queryFn: ({ signal }) => api.get<OccupancyResponse>(`/api/v1/properties/${id}/occupancy`, undefined, signal),
  });
}

function useInv() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: ['properties'] });
}

export function useSaveProperty(id?: number) {
  const inv = useInv();
  return useMutation({
    mutationFn: (body: PropertyRequest) => (id ? api.put<PropertyResponse>(`/api/v1/properties/${id}`, body) : api.post<PropertyResponse>('/api/v1/properties', body)),
    onSuccess: inv,
  });
}

export function usePropertyStatus(id: number) {
  const inv = useInv();
  return useMutation({ mutationFn: (status: PropertyStatus) => api.patch<PropertyResponse>(`/api/v1/properties/${id}/status`, { status }), onSuccess: inv });
}

export function useAssignStaff(id: number) {
  const inv = useInv();
  return {
    add: useMutation({ mutationFn: (userId: number) => api.post<AssignmentResponse>(`/api/v1/properties/${id}/assignments`, { userId }), onSuccess: inv }),
    remove: useMutation({ mutationFn: (userId: number) => api.delete(`/api/v1/properties/${id}/assignments/${userId}`), onSuccess: inv }),
  };
}
