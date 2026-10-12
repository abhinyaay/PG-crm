import { useEffect, useMemo, useState } from 'react';
import { Plus } from 'lucide-react';
import { BED_STATUSES, ROOM_TYPES, type BedStatus, type BedSummary, type RoomResponse } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { Button } from '@/components/ui/Button';
import { Select } from '@/components/ui/Field';
import { PageHeader } from '@/components/ui/PageHeader';
import { Skeleton } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { usePropertyOptions } from '@/features/shared/lookups';
import { humanize } from '@/lib/format';
import { isOperator } from '@/lib/roles';
import { useSearchState } from '@/lib/useSearchState';
import { usePropertyRooms } from './api';
import { BedBoard, BedLegend } from './BedBoard';
import { BedDetailDialog } from './BedDetailDialog';
import { RoomFormDialog } from './RoomFormDialog';

export default function BedBoardPage() {
  const user = useUser();
  const operator = isOperator(user.role);
  const s = useSearchState();
  const properties = usePropertyOptions();
  const propertyId = s.getNumber('propertyId') ?? properties.data?.[0]?.id;
  const status = (s.get('status') as BedStatus | '') || '';
  const roomType = s.get('roomType');
  const rooms = usePropertyRooms(propertyId);
  const [selected, setSelected] = useState<{ room: RoomResponse; bed: BedSummary } | null>(null);
  const [roomDialog, setRoomDialog] = useState<{ room: RoomResponse | null } | null>(null);

  // keep the selected property in the URL so the board is linkable
  useEffect(() => {
    if (!s.getNumber('propertyId') && propertyId) s.set({ propertyId });
  }, [propertyId, s]);

  const filtered = useMemo(() => (rooms.data ?? []).filter((r) => !roomType || r.roomType === roomType), [rooms.data, roomType]);
  const counts = useMemo(() => {
    const c: Partial<Record<BedStatus, number>> = {};
    for (const r of filtered) for (const b of r.beds) c[b.status] = (c[b.status] ?? 0) + 1;
    return c;
  }, [filtered]);

  return (
    <div>
      <PageHeader
        title="Bed board"
        subtitle="Tap a bed to reserve, check in or change its status"
        actions={
          operator && propertyId ? (
            <Button onClick={() => setRoomDialog({ room: null })} icon={<Plus className="h-4 w-4" aria-hidden />}>
              Add room
            </Button>
          ) : undefined
        }
      />
      <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-[minmax(0,16rem)_12rem_12rem]">
        <Select label="Property" containerClassName="col-span-2 md:col-span-1" value={propertyId ?? ''} onChange={(e) => s.set({ propertyId: e.target.value })} disabled={properties.isLoading}>
          {properties.data?.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </Select>
        <Select label="Bed status" value={status} onChange={(e) => s.set({ status: e.target.value })}>
          <option value="">All beds</option>
          {BED_STATUSES.map((b) => (
            <option key={b} value={b}>
              {humanize(b)}
            </option>
          ))}
        </Select>
        <Select label="Room type" value={roomType} onChange={(e) => s.set({ roomType: e.target.value })}>
          <option value="">All types</option>
          {ROOM_TYPES.map((b) => (
            <option key={b} value={b}>
              {humanize(b)}
            </option>
          ))}
        </Select>
      </div>
      <div className="mb-4">
        <BedLegend counts={rooms.data ? counts : undefined} />
      </div>

      {properties.data && properties.data.length === 0 ? (
        <EmptyState title="No properties yet" description="Create a property first." />
      ) : rooms.isLoading || properties.isLoading ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-32 rounded-2xl" />
          ))}
        </div>
      ) : rooms.error ? (
        <ErrorState error={rooms.error} onRetry={() => void rooms.refetch()} />
      ) : (
        <BedBoard rooms={filtered} statusFilter={status} onBedClick={(room, bed) => setSelected({ room, bed })} onEditRoom={operator ? (room) => setRoomDialog({ room }) : undefined} />
      )}

      {selected && <BedDetailDialog room={selected.room} bed={selected.bed} onClose={() => setSelected(null)} />}
      {roomDialog && propertyId && <RoomFormDialog open onClose={() => setRoomDialog(null)} propertyId={propertyId} room={roomDialog.room} />}
    </div>
  );
}
