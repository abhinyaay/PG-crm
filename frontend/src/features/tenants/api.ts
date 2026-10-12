import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/api';
import type { DocumentResponse, DocumentType, EmergencyContact, Page, TenantBalanceResponse, TenantRequest, TenantResponse, TenantSummary, VerificationStatus } from '@/api/types';

export interface TenantFilters {
  page: number;
  q?: string;
  propertyId?: number;
  status?: string;
  joinedFrom?: string;
  joinedTo?: string;
  sort?: string;
}

export function useTenantList(f: TenantFilters) {
  return useQuery({
    queryKey: ['tenants', 'list', f],
    queryFn: ({ signal }) => api.get<Page<TenantSummary>>('/api/v1/tenants', { ...f, size: 20, sort: f.sort ?? 'fullName,asc' }, signal),
    placeholderData: (p) => p,
  });
}

export function useMyTenant(enabled = true) {
  return useQuery({ queryKey: ['tenants', 'me'], enabled, queryFn: ({ signal }) => api.get<TenantResponse>('/api/v1/tenants/me', undefined, signal) });
}

export function useTenantBalance(id: number | undefined, enabled = true) {
  return useQuery({
    queryKey: ['tenants', id, 'balance'],
    enabled: id != null && enabled,
    queryFn: ({ signal }) => api.get<TenantBalanceResponse>(`/api/v1/tenants/${id}/balance`, undefined, signal),
  });
}

export function useTenantDocuments(id: number | undefined, enabled = true) {
  return useQuery({
    queryKey: ['tenants', id, 'documents'],
    enabled: id != null && enabled,
    queryFn: ({ signal }) => api.get<DocumentResponse[]>(`/api/v1/tenants/${id}/documents`, undefined, signal),
  });
}

function useInv() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: ['tenants'] });
}

export function useSaveTenant(id?: number) {
  const inv = useInv();
  return useMutation({
    mutationFn: (body: TenantRequest) => (id ? api.put<TenantResponse>(`/api/v1/tenants/${id}`, body) : api.post<TenantResponse>('/api/v1/tenants', body)),
    onSuccess: inv,
  });
}

export function useSaveContacts(id: number) {
  const inv = useInv();
  return useMutation({
    mutationFn: (contacts: EmergencyContact[]) => api.put<TenantResponse>(`/api/v1/tenants/${id}/emergency-contacts`, { contacts }),
    onSuccess: inv,
  });
}

export function useAddDocument(id: number) {
  const inv = useInv();
  return useMutation({
    mutationFn: (b: { documentType: DocumentType; documentNumber: string; storageKey?: string }) => api.post<DocumentResponse>(`/api/v1/tenants/${id}/documents`, b),
    onSuccess: inv,
  });
}

export function useReviewDocument(id: number) {
  const inv = useInv();
  return useMutation({
    mutationFn: ({ documentId, status }: { documentId: number; status: VerificationStatus }) => api.post<DocumentResponse>(`/api/v1/tenants/${id}/documents/${documentId}/review`, { status }),
    onSuccess: inv,
  });
}

export function useCreatePortalAccount(id: number) {
  const inv = useInv();
  return useMutation({
    mutationFn: (initialPassword: string) => api.post<TenantResponse>(`/api/v1/tenants/${id}/portal-account`, { initialPassword }),
    onSuccess: inv,
  });
}

export function useDeleteTenant() {
  const inv = useInv();
  return useMutation({ mutationFn: (id: number) => api.delete(`/api/v1/tenants/${id}`), onSuccess: inv });
}
