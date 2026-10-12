import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/api';
import type { CreateUserRequest, Page, UserResponse } from '@/api/types';

export function useUsers(f: { page: number; q?: string; role?: string; status?: string }) {
  return useQuery({
    queryKey: ['users', 'list', f],
    queryFn: ({ signal }) => api.get<Page<UserResponse>>('/api/v1/users', { ...f, size: 20, sort: 'fullName,asc' }, signal),
    placeholderData: (p) => p,
  });
}

export function useUserActions() {
  const qc = useQueryClient();
  const inv = () => {
    void qc.invalidateQueries({ queryKey: ['users'] });
    void qc.invalidateQueries({ queryKey: ['properties'] });
  };
  return {
    create: useMutation({ mutationFn: (b: CreateUserRequest) => api.post<UserResponse>('/api/v1/users', b), onSuccess: inv }),
    activate: useMutation({ mutationFn: (id: number) => api.post<UserResponse>(`/api/v1/users/${id}/activate`), onSuccess: inv }),
    deactivate: useMutation({ mutationFn: (id: number) => api.post<UserResponse>(`/api/v1/users/${id}/deactivate`), onSuccess: inv }),
    resetPassword: useMutation({ mutationFn: ({ id, newPassword }: { id: number; newPassword: string }) => api.post(`/api/v1/users/${id}/reset-password`, { newPassword }, { responseType: 'none' }) }),
  };
}
