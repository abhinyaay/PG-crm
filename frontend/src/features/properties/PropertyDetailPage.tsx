import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { BedDouble, Pencil, Plus, Trash2, UserPlus } from 'lucide-react';
import { api } from '@/api';
import type { Page, PropertyStatus, UserResponse } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { Button, LinkButton } from '@/components/ui/Button';
import { Card, CardBody, CardHeader, DescriptionList } from '@/components/ui/Card';
import { Input, Select } from '@/components/ui/Field';
import { KpiCard } from '@/components/ui/KpiCard';
import { Modal } from '@/components/ui/Modal';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { PageHeader } from '@/components/ui/PageHeader';
import { Skeleton, SkeletonCards } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { useToast } from '@/components/ui/Toast';
import { usePropertyStaff } from '@/features/shared/lookups';
import { useBuildings, useCreateBuilding, useCreateFloor, useFloors } from '@/features/rooms/api';
import { formatDate, formatPercent, humanize } from '@/lib/format';
import { isAdmin, isOperator, ROLE_LABELS } from '@/lib/roles';
import { useAssignStaff, useOccupancy, useProperty, usePropertyStatus } from './api';

function BuildingFloors({ propertyId, buildingId, canEdit }: { propertyId: number; buildingId: number; canEdit: boolean }) {
  const floors = useFloors(propertyId, buildingId);
  const create = useCreateFloor(propertyId, buildingId);
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [num, setNum] = useState('');
  const [label, setLabel] = useState('');
  return (
    <div className="mt-2">
      {floors.isLoading ? (
        <Skeleton className="h-6 w-40" />
      ) : (
        <div className="flex flex-wrap gap-1.5">
          {(floors.data ?? [])
            .slice()
            .sort((a, b) => a.floorNumber - b.floorNumber)
            .map((f) => (
              <span key={f.id} className="rounded-lg bg-slate-100 px-2 py-1 text-xs text-slate-700 dark:bg-slate-800 dark:text-slate-300">
                {f.label && f.label.trim().toLowerCase() !== `floor ${f.floorNumber}` ? `Floor ${f.floorNumber} · ${f.label}` : `Floor ${f.floorNumber}`}
              </span>
            ))}
          {floors.data?.length === 0 && <span className="text-xs text-slate-500">No floors yet</span>}
          {canEdit && (
            <button type="button" onClick={() => setOpen(true)} className="inline-flex min-h-[32px] items-center gap-1 rounded-lg border border-dashed border-slate-300 px-2 text-xs text-slate-600 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800">
              <Plus className="h-3 w-3" aria-hidden /> Floor
            </button>
          )}
        </div>
      )}
      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title="Add floor"
        size="sm"
        footer={
          <>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              loading={create.isPending}
              disabled={num === ''}
              onClick={async () => {
                try {
                  await create.mutateAsync({ floorNumber: Number(num), label: label || undefined });
                  toast.success('Floor added');
                  setOpen(false);
                  setNum('');
                  setLabel('');
                } catch (e) {
                  toast.apiError(e, 'Could not add floor');
                }
              }}
            >
              Add floor
            </Button>
          </>
        }
      >
        <div className="grid grid-cols-2 gap-3">
          <Input label="Floor number" type="number" inputMode="numeric" min={-5} max={200} required value={num} onChange={(e) => setNum(e.target.value)} data-autofocus />
          <Input label="Label" maxLength={50} value={label} onChange={(e) => setLabel(e.target.value)} placeholder="e.g. Ground" />
        </div>
      </Modal>
    </div>
  );
}

function AssignStaffDialog({ propertyId, open, onClose, existing }: { propertyId: number; open: boolean; onClose: () => void; existing: number[] }) {
  const toast = useToast();
  const { add } = useAssignStaff(propertyId);
  const [userId, setUserId] = useState('');
  const users = useQuery({
    queryKey: ['users', 'assignable'],
    enabled: open,
    queryFn: async ({ signal }) => {
      const [m, s] = await Promise.all([
        api.get<Page<UserResponse>>('/api/v1/users', { role: 'MANAGER', status: 'ACTIVE', size: 100 }, signal),
        api.get<Page<UserResponse>>('/api/v1/users', { role: 'STAFF', status: 'ACTIVE', size: 100 }, signal),
      ]);
      return [...m.content, ...s.content];
    },
  });
  const options = (users.data ?? []).filter((u) => !existing.includes(u.id));
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Assign staff"
      size="sm"
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            disabled={!userId}
            loading={add.isPending}
            onClick={async () => {
              try {
                await add.mutateAsync(Number(userId));
                toast.success('Staff assigned');
                setUserId('');
                onClose();
              } catch (e) {
                toast.apiError(e, 'Could not assign');
              }
            }}
          >
            Assign
          </Button>
        </>
      }
    >
      {users.isLoading ? (
        <Skeleton className="h-11" />
      ) : options.length === 0 ? (
        <EmptyState title="Everyone is already assigned" description="Create MANAGER or STAFF users on the Users page." />
      ) : (
        <Select label="Manager or staff member" value={userId} onChange={(e) => setUserId(e.target.value)} data-autofocus>
          <option value="">Select…</option>
          {options.map((u) => (
            <option key={u.id} value={u.id}>
              {u.fullName} — {ROLE_LABELS[u.role]}
            </option>
          ))}
        </Select>
      )}
    </Modal>
  );
}

export default function PropertyDetailPage() {
  const id = Number(useParams().id);
  const user = useUser();
  const toast = useToast();
  const p = useProperty(id);
  const occ = useOccupancy(id);
  const buildings = useBuildings(id);
  const staff = usePropertyStaff(id);
  const status = usePropertyStatus(id);
  const createBuilding = useCreateBuilding(id);
  const { remove } = useAssignStaff(id);
  const [assignOpen, setAssignOpen] = useState(false);
  const [removeId, setRemoveId] = useState<number | null>(null);
  const [buildingOpen, setBuildingOpen] = useState(false);
  const [bCode, setBCode] = useState('');
  const [bName, setBName] = useState('');
  const admin = isAdmin(user.role);
  const operator = isOperator(user.role);

  if (p.isLoading) return <SkeletonCards />;
  if (p.error || !p.data) return <ErrorState error={p.error} onRetry={() => void p.refetch()} />;
  const prop = p.data;

  return (
    <div className="space-y-4">
      <PageHeader
        back={{ to: '/properties', label: 'Properties' }}
        title={prop.name}
        subtitle={
          <span className="flex flex-wrap items-center gap-1.5">
            {prop.code} · {humanize(prop.type)} <StatusBadge status={prop.status} />
          </span>
        }
        actions={
          <>
            <LinkButton to={`/beds?propertyId=${id}`} variant="secondary" icon={<BedDouble className="h-4 w-4" aria-hidden />}>
              Bed board
            </LinkButton>
            {admin && (
              <LinkButton to={`/properties/${id}/edit`} variant="outline" icon={<Pencil className="h-4 w-4" aria-hidden />}>
                Edit
              </LinkButton>
            )}
          </>
        }
      />

      {occ.data ? (
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
          <KpiCard label="Occupancy" value={formatPercent(occ.data.occupancyRate)} />
          <KpiCard label="Total beds" value={occ.data.totalBeds} />
          <KpiCard label="Occupied" value={occ.data.occupiedBeds} />
          <KpiCard label="Available" value={occ.data.availableBeds} tone="good" />
          <KpiCard label="Reserved / maint." value={`${occ.data.reservedBeds} / ${occ.data.maintenanceBeds}`} tone="warn" />
        </div>
      ) : (
        <SkeletonCards count={5} />
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader
            title="Details"
            actions={
              admin ? (
                <Select
                  aria-label="Property status"
                  value={prop.status}
                  disabled={status.isPending}
                  onChange={async (e) => {
                    try {
                      await status.mutateAsync(e.target.value as PropertyStatus);
                      toast.success('Status updated');
                    } catch (err) {
                      toast.apiError(err, 'Could not change status');
                    }
                  }}
                  className="md:min-h-[36px] md:py-1"
                >
                  <option value="ACTIVE">Active</option>
                  <option value="INACTIVE">Inactive</option>
                  <option value="UNDER_RENOVATION">Under renovation</option>
                </Select>
              ) : undefined
            }
          />
          <CardBody>
            <DescriptionList
              items={[
                { label: 'Address', value: [prop.address.line1, prop.address.line2, `${prop.address.city}, ${prop.address.state} ${prop.address.postalCode}`, prop.address.country].filter(Boolean).join(', '), full: true },
                { label: 'Contact phone', value: prop.contactPhone },
                { label: 'Rent due day', value: `Day ${prop.rentDueDay} of each month` },
                { label: 'Created', value: formatDate(prop.createdAt) },
                ...(prop.description ? [{ label: 'Description', value: prop.description, full: true }] : []),
              ]}
            />
          </CardBody>
        </Card>

        <Card>
          <CardHeader
            title="Buildings & floors"
            actions={
              operator ? (
                <Button size="sm" variant="outline" onClick={() => setBuildingOpen(true)} icon={<Plus className="h-4 w-4" aria-hidden />}>
                  Building
                </Button>
              ) : undefined
            }
          />
          <CardBody>
            {buildings.isLoading ? (
              <Skeleton className="h-16" />
            ) : !buildings.data?.length ? (
              <EmptyState title="No buildings yet" />
            ) : (
              <ul className="space-y-3">
                {buildings.data.map((b) => (
                  <li key={b.id} className="rounded-xl border border-slate-200 p-3 dark:border-slate-800">
                    <p className="font-medium text-slate-900 dark:text-slate-100">
                      {b.name} <span className="text-xs text-slate-500">({b.code})</span>
                    </p>
                    <BuildingFloors propertyId={id} buildingId={b.id} canEdit={operator} />
                  </li>
                ))}
              </ul>
            )}
          </CardBody>
        </Card>

        {operator && (
          <Card className="lg:col-span-2">
            <CardHeader
              title="Staff assignments"
              subtitle="Managers and staff who can work on this property"
              actions={
                admin ? (
                  <Button size="sm" variant="outline" onClick={() => setAssignOpen(true)} icon={<UserPlus className="h-4 w-4" aria-hidden />}>
                    Assign
                  </Button>
                ) : undefined
              }
            />
            <CardBody className="p-0">
              {staff.isLoading ? (
                <div className="p-4">
                  <Skeleton className="h-12" />
                </div>
              ) : !staff.data?.length ? (
                <div className="p-4">
                  <EmptyState title="No staff assigned" />
                </div>
              ) : (
                <ul className="divide-y divide-slate-100 dark:divide-slate-800">
                  {staff.data.map((a) => (
                    <li key={a.userId} className="flex items-center justify-between gap-3 px-4 py-3">
                      <span className="min-w-0">
                        <span className="block truncate font-medium text-slate-900 dark:text-slate-100">{a.fullName}</span>
                        <span className="block truncate text-xs text-slate-500">
                          {ROLE_LABELS[a.role]} · {a.email} · since {formatDate(a.assignedAt)}
                        </span>
                      </span>
                      {admin && (
                        <Button variant="ghost" size="icon" aria-label={`Remove ${a.fullName}`} onClick={() => setRemoveId(a.userId)}>
                          <Trash2 className="h-4 w-4 text-red-600" aria-hidden />
                        </Button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </CardBody>
          </Card>
        )}
      </div>

      <AssignStaffDialog propertyId={id} open={assignOpen} onClose={() => setAssignOpen(false)} existing={(staff.data ?? []).map((a) => a.userId)} />
      <ConfirmDialog
        open={removeId !== null}
        title="Remove assignment?"
        description="They will lose access to this property's residents, rooms and tickets."
        confirmLabel="Remove"
        loading={remove.isPending}
        onClose={() => setRemoveId(null)}
        onConfirm={async () => {
          try {
            await remove.mutateAsync(removeId!);
            toast.success('Assignment removed');
            setRemoveId(null);
          } catch (e) {
            toast.apiError(e, 'Could not remove');
          }
        }}
      />
      <Modal
        open={buildingOpen}
        onClose={() => setBuildingOpen(false)}
        title="Add building"
        size="sm"
        footer={
          <>
            <Button variant="outline" onClick={() => setBuildingOpen(false)}>
              Cancel
            </Button>
            <Button
              loading={createBuilding.isPending}
              disabled={!/^[A-Z0-9-]{1,20}$/.test(bCode) || !bName.trim()}
              onClick={async () => {
                try {
                  await createBuilding.mutateAsync({ code: bCode, name: bName.trim() });
                  toast.success('Building added');
                  setBuildingOpen(false);
                  setBCode('');
                  setBName('');
                } catch (e) {
                  toast.apiError(e, 'Could not add building');
                }
              }}
            >
              Add
            </Button>
          </>
        }
      >
        <div className="grid grid-cols-2 gap-3">
          <Input label="Code" required hint="A-Z, 0-9, -" value={bCode} onChange={(e) => setBCode(e.target.value.toUpperCase())} maxLength={20} data-autofocus />
          <Input label="Name" required value={bName} onChange={(e) => setBName(e.target.value)} maxLength={100} />
        </div>
      </Modal>
    </div>
  );
}
