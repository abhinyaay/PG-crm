import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/api';
import type { CommentResponse, ComplaintActivity, ComplaintCategory, ComplaintResponse, ComplaintStatus, Page, Priority } from '@/api/types';

export interface ComplaintFilters {
  page?: number;
  propertyId?: number;
  status?: string;
  category?: string;
  priority?: string;
  minEscalationLevel?: number;
  assignedToMe?: boolean;
}

export function useComplaintList(f: ComplaintFilters) {
  return useQuery({
    queryKey: ['complaints', 'list', f],
    queryFn: ({ signal }) =>
      api.get<Page<ComplaintResponse>>('/api/v1/complaints', { ...f, size: 20, assignedToMe: f.assignedToMe || undefined, sort: 'createdAt,desc' }, signal),
    placeholderData: (prev) => prev,
  });
}

export function useComplaint(id: number) {
  return useQuery({ queryKey: ['complaints', id], queryFn: ({ signal }) => api.get<ComplaintResponse>(`/api/v1/complaints/${id}`, undefined, signal) });
}

export function useComplaintComments(id: number) {
  return useQuery({ queryKey: ['complaints', id, 'comments'], queryFn: ({ signal }) => api.get<CommentResponse[]>(`/api/v1/complaints/${id}/comments`, undefined, signal) });
}

export function useComplaintHistory(id: number) {
  return useQuery({ queryKey: ['complaints', id, 'history'], queryFn: ({ signal }) => api.get<ComplaintActivity[]>(`/api/v1/complaints/${id}/history`, undefined, signal) });
}

function useInv() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: ['complaints'] });
    void qc.invalidateQueries({ queryKey: ['dashboard'] });
  };
}

export function useCreateComplaint() {
  const inv = useInv();
  return useMutation({
    mutationFn: (body: { propertyId?: number; category: ComplaintCategory; priority: Priority; subject: string; description: string }) =>
      api.post<ComplaintResponse>('/api/v1/complaints', body),
    onSuccess: inv,
  });
}

export function useComplaintActions(id: number) {
  const inv = useInv();
  return {
    status: useMutation({
      mutationFn: (b: { status: ComplaintStatus; resolution?: string }) => api.post<ComplaintResponse>(`/api/v1/complaints/${id}/status`, b),
      onSuccess: inv,
    }),
    escalate: useMutation({ mutationFn: (reason: string) => api.post<ComplaintResponse>(`/api/v1/complaints/${id}/escalate`, { reason }), onSuccess: inv }),
    assign: useMutation({ mutationFn: (assigneeUserId: number) => api.post<ComplaintResponse>(`/api/v1/complaints/${id}/assign`, { assigneeUserId }), onSuccess: inv }),
    comment: useMutation({ mutationFn: (b: { body: string; internal: boolean }) => api.post<CommentResponse>(`/api/v1/complaints/${id}/comments`, b), onSuccess: inv }),
  };
}
