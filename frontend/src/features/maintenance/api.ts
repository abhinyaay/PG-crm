import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/api';
import type { MaintenanceCategory, MaintenanceHistoryEntry, MaintenanceResponse, MaintenanceStatus, Page, Priority } from '@/api/types';

export interface MaintenanceFilters {
  page?: number;
  size?: number;
  propertyId?: number;
  status?: string;
  priority?: string;
  category?: string;
  slaBreached?: boolean;
  assignedToMe?: boolean;
  sort?: string;
}

export function useMaintenanceList(f: MaintenanceFilters) {
  return useQuery({
    queryKey: ['maintenance', 'list', f],
    queryFn: ({ signal }) =>
      api.get<Page<MaintenanceResponse>>(
        '/api/v1/maintenance',
        { ...f, size: f.size ?? 20, slaBreached: f.slaBreached || undefined, assignedToMe: f.assignedToMe || undefined, sort: f.sort ?? 'createdAt,desc' },
        signal,
      ),
    placeholderData: (prev) => prev,
  });
}

export function useMaintenance(id: number) {
  return useQuery({
    queryKey: ['maintenance', id],
    queryFn: ({ signal }) => api.get<MaintenanceResponse>(`/api/v1/maintenance/${id}`, undefined, signal),
  });
}

export function useMaintenanceHistory(id: number) {
  return useQuery({
    queryKey: ['maintenance', id, 'history'],
    queryFn: ({ signal }) => api.get<MaintenanceHistoryEntry[]>(`/api/v1/maintenance/${id}/history`, undefined, signal),
  });
}

export interface CreateMaintenance {
  propertyId?: number;
  roomId?: number;
  category: MaintenanceCategory;
  priority: Priority;
  title: string;
  description: string;
}

function useInvalidate() {
  const qc = useQueryClient();
  return (id?: number) => {
    void qc.invalidateQueries({ queryKey: ['maintenance'] });
    void qc.invalidateQueries({ queryKey: ['dashboard'] });
    if (id) void qc.invalidateQueries({ queryKey: ['maintenance', id] });
  };
}

export function useCreateMaintenance() {
  const inv = useInvalidate();
  return useMutation({
    mutationFn: (body: CreateMaintenance) => api.post<MaintenanceResponse>('/api/v1/maintenance', body),
    onSuccess: () => inv(),
  });
}

export function useAssignMaintenance(id: number) {
  const inv = useInvalidate();
  return useMutation({
    mutationFn: (assigneeUserId: number) => api.post<MaintenanceResponse>(`/api/v1/maintenance/${id}/assign`, { assigneeUserId }),
    onSuccess: () => inv(id),
  });
}

export function useChangeMaintenanceStatus(id: number) {
  const inv = useInvalidate();
  return useMutation({
    mutationFn: (body: { status: MaintenanceStatus; notes?: string }) => api.post<MaintenanceResponse>(`/api/v1/maintenance/${id}/status`, body),
    onSuccess: () => inv(id),
  });
}
