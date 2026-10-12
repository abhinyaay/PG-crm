import { useQuery } from '@tanstack/react-query';
import { api } from '@/api';
import type { AssignmentResponse, Page, PropertyResponse, TenantResponse, UserResponse } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import { isAdmin, isOperator, isStaffOrAbove } from '@/lib/roles';

/** All properties in the caller's scope (orgs have a handful; the API caps a page at 100). */
export function usePropertyOptions() {
  const { user } = useAuth();
  return useQuery({
    queryKey: ['properties', 'options'],
    queryFn: ({ signal }) => api.get<Page<PropertyResponse>>('/api/v1/properties', { size: 100, sort: 'name,asc' }, signal),
    select: (p) => p.content,
    staleTime: 5 * 60_000,
    enabled: isStaffOrAbove(user?.role),
  });
}

export function usePropertyName(id: number | null | undefined): string {
  const { data } = usePropertyOptions();
  if (id == null) return '—';
  return data?.find((p) => p.id === id)?.name ?? `Property #${id}`;
}

/** Managers and staff assigned to a property (used for assignee pickers and name lookups). */
export function usePropertyStaff(propertyId: number | null | undefined) {
  const { user } = useAuth();
  return useQuery({
    queryKey: ['properties', propertyId, 'assignments'],
    queryFn: ({ signal }) => api.get<AssignmentResponse[]>(`/api/v1/properties/${propertyId}/assignments`, undefined, signal),
    enabled: propertyId != null && isOperator(user?.role),
    staleTime: 5 * 60_000,
  });
}

/** Organization user directory (admins only; organizations have a handful of staff accounts). */
export function useUserDirectory() {
  const { user } = useAuth();
  return useQuery({
    queryKey: ['users', 'directory'],
    enabled: isAdmin(user?.role),
    staleTime: 5 * 60_000,
    queryFn: ({ signal }) => api.get<Page<UserResponse>>('/api/v1/users', { size: 100, sort: 'fullName,asc' }, signal),
    select: (p) => p.content,
  });
}

/**
 * Display name for a user id: the admin user directory when available, otherwise the property's staff assignments
 * (managers), otherwise a neutral "User #id" (staff and residents cannot list users).
 */
export function useUserLabel(userId: number | null | undefined, propertyId?: number | null): string {
  const { user } = useAuth();
  const directory = useUserDirectory();
  const staff = usePropertyStaff(isAdmin(user?.role) ? null : propertyId);
  if (userId == null) return 'Unassigned';
  if (user?.id === userId) return 'You';
  const fromDirectory = directory.data?.find((u) => u.id === userId);
  if (fromDirectory) return fromDirectory.fullName;
  const match = staff.data?.find((a) => a.userId === userId);
  return match ? match.fullName : `User #${userId}`;
}

export function useTenant(id: number | null | undefined) {
  return useQuery({
    queryKey: ['tenants', id],
    queryFn: ({ signal }) => api.get<TenantResponse>(`/api/v1/tenants/${id}`, undefined, signal),
    enabled: id != null,
    staleTime: 60_000,
  });
}
