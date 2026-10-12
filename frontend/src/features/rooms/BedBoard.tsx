import { BedDouble, Bath, Snowflake, UserRound, Clock, Wrench, Pencil } from 'lucide-react';
import type { BedStatus, BedSummary, RoomResponse } from '@/api/types';
import { cn } from '@/lib/cn';
import { formatMoney, humanize } from '@/lib/format';

export const BED_STYLES: Record<BedStatus, { chip: string; label: string; Icon: typeof BedDouble }> = {
  AVAILABLE: {
    chip: 'border-emerald-300 bg-emerald-50 text-emerald-800 hover:bg-emerald-100 dark:border-emerald-800 dark:bg-emerald-950/60 dark:text-emerald-200',
    label: 'Available',
    Icon: BedDouble,
  },
  OCCUPIED: {
    chip: 'border-brand-300 bg-brand-100 text-brand-900 hover:bg-brand-200 dark:border-brand-800 dark:bg-brand-900/60 dark:text-brand-100',
    label: 'Occupied',
    Icon: UserRound,
  },
  RESERVED: {
    chip: 'border-amber-300 bg-amber-50 text-amber-900 hover:bg-amber-100 dark:border-amber-800 dark:bg-amber-950/60 dark:text-amber-200',
    label: 'Reserved',
    Icon: Clock,
  },
  MAINTENANCE: {
    chip: 'border-red-300 bg-red-50 text-red-800 hover:bg-red-100 bg-stripes dark:border-red-900 dark:bg-red-950/60 dark:text-red-200',
    label: 'Maintenance',
    Icon: Wrench,
  },
};

export function BedLegend({ counts }: { counts?: Partial<Record<BedStatus, number>> }) {
  return (
    <ul className="flex flex-wrap gap-2 text-xs" aria-label="Bed status legend">
      {(Object.keys(BED_STYLES) as BedStatus[]).map((s) => {
        const { chip, label, Icon } = BED_STYLES[s];
        return (
          <li key={s} className={cn('inline-flex items-center gap-1 rounded-full border px-2 py-1 font-medium', chip)}>
            <Icon className="h-3.5 w-3.5" aria-hidden />
            {label}
            {counts && <span className="tabular-nums">· {counts[s] ?? 0}</span>}
          </li>
        );
      })}
    </ul>
  );
}

interface BedBoardProps {
  rooms: RoomResponse[];
  onBedClick?: (room: RoomResponse, bed: BedSummary) => void;
  onEditRoom?: (room: RoomResponse) => void;
  /** Only show beds with this status (rooms without matching beds are hidden). */
  statusFilter?: BedStatus | '';
}

interface FloorGroup {
  key: string;
  title: string;
  rooms: RoomResponse[];
}

export function groupRoomsByFloor(rooms: RoomResponse[]): FloorGroup[] {
  const map = new Map<string, FloorGroup>();
  for (const r of rooms) {
    const key = `${r.buildingCode}|${r.floorNumber}`;
    if (!map.has(key)) map.set(key, { key, title: `Block ${r.buildingCode} · Floor ${r.floorNumber}`, rooms: [] });
    map.get(key)!.rooms.push(r);
  }
  return [...map.values()]
    .sort((a, b) => a.key.localeCompare(b.key, undefined, { numeric: true }))
    .map((g) => ({ ...g, rooms: g.rooms.sort((a, b) => a.roomNumber.localeCompare(b.roomNumber, undefined, { numeric: true })) }));
}

/** Visual bed board: rooms as cards (grouped by block/floor), beds as colored chips by status. */
export function BedBoard({ rooms, onBedClick, onEditRoom, statusFilter }: BedBoardProps) {
  const visible = statusFilter ? rooms.filter((r) => r.beds.some((b) => b.status === statusFilter)) : rooms;
  const groups = groupRoomsByFloor(visible);
  if (groups.length === 0) {
    return <p className="rounded-2xl border border-dashed border-slate-300 p-8 text-center text-sm text-slate-500 dark:border-slate-700">No rooms match.</p>;
  }
  return (
    <div className="space-y-6">
      {groups.map((g) => (
        <section key={g.key} aria-labelledby={`floor-${g.key}`}>
          <h2 id={`floor-${g.key}`} className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
            {g.title}
          </h2>
          <ul className="grid grid-cols-1 gap-3 min-[420px]:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {g.rooms.map((room) => {
              const free = room.beds.filter((b) => b.status === 'AVAILABLE').length;
              return (
                <li
                  key={room.id}
                  data-testid={`room-${room.roomNumber}`}
                  className={cn(
                    'rounded-2xl border bg-white p-3 shadow-sm dark:bg-slate-900',
                    room.status === 'INACTIVE' ? 'border-dashed border-slate-300 opacity-70 dark:border-slate-700' : 'border-slate-200 dark:border-slate-800',
                  )}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="font-semibold text-slate-900 dark:text-slate-100">
                        Room {room.roomNumber}
                        {room.status === 'INACTIVE' && <span className="ml-2 text-xs font-normal text-slate-500">(inactive)</span>}
                      </p>
                      <p className="flex flex-wrap items-center gap-x-2 text-xs text-slate-500 dark:text-slate-400">
                        <span>{humanize(room.roomType)}</span>
                        <span>{formatMoney(room.monthlyRent)}/mo</span>
                        {room.airConditioned && (
                          <span className="inline-flex items-center gap-0.5" title="Air conditioned">
                            <Snowflake className="h-3 w-3" aria-hidden />
                            AC
                          </span>
                        )}
                        {room.attachedBathroom && (
                          <span className="inline-flex items-center gap-0.5" title="Attached bathroom">
                            <Bath className="h-3 w-3" aria-hidden />
                            Bath
                          </span>
                        )}
                      </p>
                    </div>
                    <div className="flex shrink-0 items-center gap-1">
                      <span className="text-xs text-slate-500" aria-label={`${free} of ${room.beds.length} beds free`}>
                        {free}/{room.beds.length} free
                      </span>
                      {onEditRoom && (
                        <button
                          type="button"
                          onClick={() => onEditRoom(room)}
                          className="inline-flex h-9 w-9 items-center justify-center rounded-lg text-slate-500 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 dark:hover:bg-slate-800"
                          aria-label={`Edit room ${room.roomNumber}`}
                        >
                          <Pencil className="h-4 w-4" aria-hidden />
                        </button>
                      )}
                    </div>
                  </div>
                  <ul className="mt-3 flex flex-wrap gap-2" aria-label={`Beds in room ${room.roomNumber}`}>
                    {room.beds
                      .slice()
                      .sort((a, b) => a.bedCode.localeCompare(b.bedCode))
                      .map((bed) => {
                        const st = BED_STYLES[bed.status];
                        const dim = statusFilter && bed.status !== statusFilter;
                        return (
                          <li key={bed.id}>
                            <button
                              type="button"
                              data-status={bed.status}
                              onClick={() => onBedClick?.(room, bed)}
                              disabled={!onBedClick}
                              aria-label={`Room ${room.roomNumber}, bed ${bed.bedCode}: ${st.label}`}
                              className={cn(
                                'inline-flex min-h-touch min-w-[4.25rem] items-center justify-center gap-1.5 rounded-xl border px-3 text-sm font-semibold transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-1 disabled:cursor-default',
                                st.chip,
                                dim && 'opacity-30',
                              )}
                            >
                              <st.Icon className="h-4 w-4" aria-hidden />
                              {bed.bedCode}
                            </button>
                          </li>
                        );
                      })}
                  </ul>
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </div>
  );
}
