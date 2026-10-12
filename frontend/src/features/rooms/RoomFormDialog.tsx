import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ROOM_TYPES, type RoomResponse } from '@/api/types';
import { Button } from '@/components/ui/Button';
import { Checkbox, Input, Select } from '@/components/ui/Field';
import { Modal } from '@/components/ui/Modal';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/components/ui/Toast';
import { humanize } from '@/lib/format';
import { useFormError } from '@/lib/useFormError';
import { useBuildings, useCreateRoom, useFloors, useRoomStatus, useUpdateRoom } from './api';

const DEFAULT_CAPACITY: Record<string, number> = { SINGLE: 1, DOUBLE: 2, TRIPLE: 3, QUAD: 4, DORMITORY: 6 };

const schema = z.object({
  buildingId: z.string().optional(),
  floorId: z.string().optional(),
  roomNumber: z.string().trim().optional(),
  roomType: z.enum(ROOM_TYPES),
  capacity: z.coerce.number().int().min(1, '1–20').max(20, '1–20'),
  monthlyRent: z.coerce.number({ invalid_type_error: 'Enter an amount' }).min(0, 'Must be ≥ 0'),
  securityDeposit: z.coerce.number({ invalid_type_error: 'Enter an amount' }).min(0, 'Must be ≥ 0'),
  airConditioned: z.boolean(),
  attachedBathroom: z.boolean(),
});
type Values = z.infer<typeof schema>;

export function RoomFormDialog({ open, onClose, propertyId, room }: { open: boolean; onClose: () => void; propertyId: number; room?: RoomResponse | null }) {
  const editing = !!room;
  const toast = useToast();
  const onError = useFormError();
  const create = useCreateRoom(propertyId);
  const update = useUpdateRoom(room?.id ?? 0);
  const roomStatus = useRoomStatus();
  const [confirmDeactivate, setConfirmDeactivate] = useState(false);
  const buildings = useBuildings(open && !editing ? propertyId : undefined);
  const {
    register,
    handleSubmit,
    watch,
    reset,
    setValue,
    setError,
    formState: { errors },
  } = useForm<Values>({
    resolver: zodResolver(
      editing
        ? schema
        : schema
            .refine((v) => !!v.floorId, { path: ['floorId'], message: 'Select a floor' })
            .refine((v) => !!v.roomNumber && /^[A-Za-z0-9-]{1,20}$/.test(v.roomNumber), { path: ['roomNumber'], message: '1–20 letters, digits or -' }),
    ),
  });
  const buildingId = watch('buildingId');
  const floors = useFloors(open && !editing ? propertyId : undefined, buildingId ? Number(buildingId) : undefined);

  useEffect(() => {
    if (!open) return;
    reset(
      room
        ? { roomType: room.roomType, capacity: room.capacity, monthlyRent: room.monthlyRent, securityDeposit: room.securityDeposit, airConditioned: room.airConditioned, attachedBathroom: room.attachedBathroom }
        : { buildingId: '', floorId: '', roomNumber: '', roomType: 'DOUBLE', capacity: 2, monthlyRent: 10000, securityDeposit: 20000, airConditioned: false, attachedBathroom: true },
    );
  }, [open, room, reset]);

  useEffect(() => {
    if (!editing && buildings.data?.length === 1 && !buildingId) setValue('buildingId', String(buildings.data[0]!.id));
  }, [buildings.data, buildingId, editing, setValue]);

  const submit = handleSubmit(async (v) => {
    const body = {
      roomType: v.roomType,
      capacity: v.capacity,
      monthlyRent: v.monthlyRent,
      securityDeposit: v.securityDeposit,
      airConditioned: v.airConditioned,
      attachedBathroom: v.attachedBathroom,
    };
    try {
      if (editing) {
        await update.mutateAsync(body);
        toast.success(`Room ${room!.roomNumber} updated`);
      } else {
        const r = await create.mutateAsync({ ...body, floorId: Number(v.floorId), roomNumber: v.roomNumber! });
        toast.success(`Room ${r.roomNumber} created`, `${r.beds.length} bed(s) generated`);
      }
      onClose();
    } catch (e) {
      onError(e, setError, ['floorId', 'roomNumber', 'roomType', 'capacity', 'monthlyRent', 'securityDeposit'], editing ? 'Could not update room' : 'Could not create room');
    }
  });

  const pending = create.isPending || update.isPending;
  return (
    <>
      <Modal
        open={open}
        onClose={onClose}
        dismissible={!pending}
        title={editing ? `Edit room ${room!.roomNumber}` : 'New room'}
        description={editing ? 'Reducing capacity only removes free beds.' : 'Beds are generated automatically from the capacity.'}
        footer={
          <>
            {editing && (
              <Button variant="ghost" className="sm:mr-auto" onClick={() => (room!.status === 'ACTIVE' ? setConfirmDeactivate(true) : void roomStatus.mutateAsync({ id: room!.id, status: 'ACTIVE' }).then(() => { toast.success('Room activated'); onClose(); }, (e: unknown) => toast.apiError(e)))}>
                {room!.status === 'ACTIVE' ? 'Deactivate room' : 'Activate room'}
              </Button>
            )}
            <Button variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button loading={pending} onClick={() => void submit()}>
              {editing ? 'Save' : 'Create room'}
            </Button>
          </>
        }
      >
        <form onSubmit={submit} noValidate className="grid grid-cols-2 gap-3">
          {!editing && (
            <>
              <Select label="Building" {...register('buildingId')} disabled={buildings.isLoading}>
                <option value="">Select…</option>
                {buildings.data?.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name} ({b.code})
                  </option>
                ))}
              </Select>
              <Select label="Floor" required {...register('floorId')} disabled={!buildingId || floors.isLoading} error={errors.floorId?.message}>
                <option value="">Select…</option>
                {floors.data
                  ?.slice()
                  .sort((a, b) => a.floorNumber - b.floorNumber)
                  .map((f) => (
                    <option key={f.id} value={f.id}>
                      Floor {f.floorNumber}
                      {f.label ? ` (${f.label})` : ''}
                    </option>
                  ))}
              </Select>
              <Input label="Room number" required containerClassName="col-span-2" maxLength={20} error={errors.roomNumber?.message} {...register('roomNumber')} />
            </>
          )}
          <Select
            label="Room type"
            required
            {...register('roomType', {
              onChange: (e: { target: { value: string } }) => {
                const cap = DEFAULT_CAPACITY[e.target.value];
                if (cap && !editing) setValue('capacity', cap);
              },
            })}
          >
            {ROOM_TYPES.map((t) => (
              <option key={t} value={t}>
                {humanize(t)}
              </option>
            ))}
          </Select>
          <Input label="Capacity (beds)" type="number" inputMode="numeric" min={1} max={20} required error={errors.capacity?.message} {...register('capacity')} />
          <Input label="Monthly rent (₹)" type="number" inputMode="decimal" min={0} step="0.01" required error={errors.monthlyRent?.message} {...register('monthlyRent')} />
          <Input label="Deposit (₹)" type="number" inputMode="decimal" min={0} step="0.01" required error={errors.securityDeposit?.message} {...register('securityDeposit')} />
          <Checkbox label="Air conditioned" {...register('airConditioned')} />
          <Checkbox label="Attached bathroom" {...register('attachedBathroom')} />
        </form>
      </Modal>
      <ConfirmDialog
        open={confirmDeactivate}
        title={`Deactivate room ${room?.roomNumber ?? ''}?`}
        description="Inactive rooms cannot be booked. Rooms with occupied or reserved beds cannot be deactivated."
        confirmLabel="Deactivate"
        loading={roomStatus.isPending}
        onClose={() => setConfirmDeactivate(false)}
        onConfirm={async () => {
          try {
            await roomStatus.mutateAsync({ id: room!.id, status: 'INACTIVE' });
            toast.success('Room deactivated');
            setConfirmDeactivate(false);
            onClose();
          } catch (e) {
            toast.apiError(e, 'Could not deactivate');
          }
        }}
      />
    </>
  );
}
