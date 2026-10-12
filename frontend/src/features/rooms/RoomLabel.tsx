import { useQuery } from '@tanstack/react-query';
import { api } from '@/api';
import type { RoomResponse } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import { isStaffOrAbove } from '@/lib/roles';

export function useRoom(id: number | null | undefined, enabled = true) {
  return useQuery({
    queryKey: ['rooms', id],
    enabled: id != null && enabled,
    queryFn: ({ signal }) => api.get<RoomResponse>(`/api/v1/rooms/${id}`, undefined, signal),
    staleTime: 5 * 60_000,
  });
}

export function RoomLabel({ id, fallback = 'Common area' }: { id: number | null | undefined; fallback?: string }) {
  const { user } = useAuth();
  const canSee = isStaffOrAbove(user?.role);
  const room = useRoom(id, canSee);
  if (id == null) return <>{fallback}</>;
  if (!canSee) return <>Your room</>;
  if (!room.data) return <>Room #{id}</>;
  return (
    <>
      Room {room.data.roomNumber} · {room.data.buildingCode}, floor {room.data.floorNumber}
    </>
  );
}
