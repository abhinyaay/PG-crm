import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useNavigate } from 'react-router-dom';
import { MAINTENANCE_CATEGORIES, PRIORITIES } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { Button } from '@/components/ui/Button';
import { Card, CardBody } from '@/components/ui/Card';
import { Input, Select, Textarea } from '@/components/ui/Field';
import { PageHeader } from '@/components/ui/PageHeader';
import { StickyActions } from '@/components/ui/StickyActions';
import { useToast } from '@/components/ui/Toast';
import { PropertySelect } from '@/features/shared/PropertySelect';
import { usePropertyRooms } from '@/features/rooms/api';
import { humanize } from '@/lib/format';
import { useCreateMaintenance } from './api';
import { useFormError } from '@/lib/useFormError';

const schema = z.object({
  propertyId: z.string().optional(),
  roomId: z.string().optional(),
  category: z.enum(MAINTENANCE_CATEGORIES),
  priority: z.enum(PRIORITIES),
  title: z.string().trim().min(3, 'Give the request a short title').max(150),
  description: z.string().trim().min(5, 'Describe the problem').max(4000),
});
type Values = z.infer<typeof schema>;

export default function MaintenanceCreatePage() {
  const user = useUser();
  const isTenant = user.role === 'TENANT';
  const navigate = useNavigate();
  const toast = useToast();
  const create = useCreateMaintenance();
  const onError = useFormError();
  const {
    register,
    handleSubmit,
    watch,
    setError,
    formState: { errors },
  } = useForm<Values>({
    resolver: zodResolver(isTenant ? schema : schema.refine((v) => !!v.propertyId, { message: 'Select a property', path: ['propertyId'] })),
    defaultValues: { category: 'PLUMBING', priority: 'MEDIUM', title: '', description: '', propertyId: '', roomId: '' },
  });
  const propertyId = watch('propertyId');
  const rooms = usePropertyRooms(!isTenant && propertyId ? Number(propertyId) : undefined);

  const submit = handleSubmit(async (v) => {
    try {
      const t = await create.mutateAsync({
        propertyId: isTenant ? undefined : Number(v.propertyId),
        roomId: v.roomId ? Number(v.roomId) : undefined,
        category: v.category,
        priority: v.priority,
        title: v.title,
        description: v.description,
      });
      toast.success('Request raised', `${t.ticketNumber} has been created.`);
      navigate(`/maintenance/${t.id}`, { replace: true });
    } catch (e) {
      onError(e, setError, ['propertyId', 'roomId', 'category', 'priority', 'title', 'description'], 'Could not raise request');
    }
  });

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader title="New maintenance request" back={{ to: '/maintenance', label: 'Maintenance' }} />
      <form onSubmit={submit} noValidate>
        <Card>
          <CardBody className="space-y-4">
            {!isTenant && (
              <div className="grid gap-4 sm:grid-cols-2">
                <PropertySelect allLabel={null} required error={errors.propertyId?.message} {...register('propertyId')} />
                <Select label="Room (optional)" disabled={!propertyId || rooms.isLoading} {...register('roomId')}>
                  <option value="">Common area / not room-specific</option>
                  {rooms.data?.map((r) => (
                    <option key={r.id} value={r.id}>
                      Room {r.roomNumber} ({r.buildingCode}, floor {r.floorNumber})
                    </option>
                  ))}
                </Select>
              </div>
            )}
            {isTenant && <p className="rounded-xl bg-slate-50 p-3 text-sm text-slate-600 dark:bg-slate-800/50 dark:text-slate-300">The request is linked to your current room automatically.</p>}
            <div className="grid grid-cols-2 gap-4">
              <Select label="Category" required error={errors.category?.message} {...register('category')}>
                {MAINTENANCE_CATEGORIES.map((c) => (
                  <option key={c} value={c}>
                    {humanize(c)}
                  </option>
                ))}
              </Select>
              <Select label="Priority" required error={errors.priority?.message} {...register('priority')}>
                {PRIORITIES.map((c) => (
                  <option key={c} value={c}>
                    {humanize(c)}
                  </option>
                ))}
              </Select>
            </div>
            <Input label="Title" required maxLength={150} placeholder="e.g. Bathroom tap leaking" error={errors.title?.message} {...register('title')} />
            <Textarea label="Description" required rows={5} maxLength={4000} placeholder="What is wrong, where, since when?" error={errors.description?.message} {...register('description')} />
          </CardBody>
        </Card>
        <StickyActions>
          <Button variant="outline" onClick={() => navigate(-1)}>
            Cancel
          </Button>
          <Button type="submit" loading={create.isPending}>
            Raise request
          </Button>
        </StickyActions>
      </form>
    </div>
  );
}
