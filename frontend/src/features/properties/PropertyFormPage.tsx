import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useNavigate, useParams } from 'react-router-dom';
import { useUser } from '@/auth/AuthProvider';
import { Button } from '@/components/ui/Button';
import { Card, CardBody, CardHeader } from '@/components/ui/Card';
import { Input, Select, Textarea } from '@/components/ui/Field';
import { PageHeader } from '@/components/ui/PageHeader';
import { Skeleton } from '@/components/ui/Skeleton';
import { StickyActions } from '@/components/ui/StickyActions';
import { useToast } from '@/components/ui/Toast';
import { useFormError } from '@/lib/useFormError';
import { useProperty, useSaveProperty } from './api';

const schema = z.object({
  code: z.string().trim().regex(/^[A-Z0-9-]{2,30}$/, '2–30 characters: A–Z, 0–9 and -'),
  name: z.string().trim().min(2, 'Name is required').max(150),
  description: z.string().trim().max(2000).optional(),
  type: z.enum(['MEN', 'WOMEN', 'CO_LIVING']),
  contactPhone: z.string().trim().max(20).optional(),
  rentDueDay: z.coerce.number().int().min(1, '1–28').max(28, '1–28'),
  organizationId: z.string().optional(),
  address: z.object({
    line1: z.string().trim().min(1, 'Address is required').max(255),
    line2: z.string().trim().max(255).optional(),
    city: z.string().trim().min(1, 'City is required').max(100),
    state: z.string().trim().min(1, 'State is required').max(100),
    postalCode: z.string().trim().regex(/^[A-Za-z0-9 -]{3,12}$/, 'Enter a valid postal code'),
    country: z.string().trim().max(60).optional(),
  }),
});
type Values = z.infer<typeof schema>;
const FIELDS = ['code', 'name', 'description', 'type', 'contactPhone', 'rentDueDay', 'address', 'organizationId'];

export default function PropertyFormPage() {
  const params = useParams();
  const id = params.id ? Number(params.id) : undefined;
  const user = useUser();
  const navigate = useNavigate();
  const toast = useToast();
  const onError = useFormError();
  const existing = useProperty(id);
  const save = useSaveProperty(id);
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { code: '', name: '', type: 'CO_LIVING', rentDueDay: 5, address: { line1: '', city: '', state: '', postalCode: '', country: 'India' } },
  });

  useEffect(() => {
    if (existing.data) {
      const p = existing.data;
      reset({ code: p.code, name: p.name, description: p.description ?? '', type: p.type, contactPhone: p.contactPhone ?? '', rentDueDay: p.rentDueDay, address: { ...p.address, line2: p.address.line2 ?? '', country: p.address.country ?? '' } });
    }
  }, [existing.data, reset]);

  const submit = handleSubmit(async (v) => {
    const body = {
      ...(id ? {} : { code: v.code }),
      name: v.name,
      description: v.description || undefined,
      type: v.type,
      contactPhone: v.contactPhone || undefined,
      rentDueDay: v.rentDueDay,
      address: { ...v.address, line2: v.address.line2 || undefined, country: v.address.country || undefined },
      ...(user.role === 'SUPER_ADMIN' && v.organizationId ? { organizationId: Number(v.organizationId) } : {}),
    };
    try {
      const p = await save.mutateAsync(body);
      toast.success(id ? 'Property updated' : 'Property created');
      navigate(`/properties/${p.id}`, { replace: true });
    } catch (e) {
      onError(e, setError, FIELDS);
    }
  });

  if (id && existing.isLoading) return <Skeleton className="h-96" />;

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader title={id ? 'Edit property' : 'New property'} back={{ to: id ? `/properties/${id}` : '/properties', label: id ? 'Property' : 'Properties' }} />
      <form onSubmit={submit} noValidate className="space-y-4">
        <Card>
          <CardHeader title="Basics" />
          <CardBody className="grid gap-4 sm:grid-cols-2">
            <Input label="Code" required disabled={!!id} hint={id ? 'The code cannot be changed' : 'e.g. SUN-KOR'} error={errors.code?.message} {...register('code', { setValueAs: (v: string) => v.toUpperCase() })} />
            <Input label="Name" required error={errors.name?.message} {...register('name')} />
            <Select label="Type" required {...register('type')}>
              <option value="MEN">Men</option>
              <option value="WOMEN">Women</option>
              <option value="CO_LIVING">Co-living</option>
            </Select>
            <Input label="Rent due day" type="number" inputMode="numeric" min={1} max={28} required error={errors.rentDueDay?.message} {...register('rentDueDay')} />
            <Input label="Contact phone" type="tel" inputMode="tel" error={errors.contactPhone?.message} {...register('contactPhone')} />
            {user.role === 'SUPER_ADMIN' && !id && <Input label="Organization id" type="number" inputMode="numeric" hint="Required for platform admins" {...register('organizationId')} />}
            <Textarea label="Description" containerClassName="sm:col-span-2" rows={3} error={errors.description?.message} {...register('description')} />
          </CardBody>
        </Card>
        <Card>
          <CardHeader title="Address" />
          <CardBody className="grid gap-4 sm:grid-cols-2">
            <Input label="Address line 1" required containerClassName="sm:col-span-2" autoComplete="address-line1" error={errors.address?.line1?.message} {...register('address.line1')} />
            <Input label="Address line 2" containerClassName="sm:col-span-2" autoComplete="address-line2" {...register('address.line2')} />
            <Input label="City" required autoComplete="address-level2" error={errors.address?.city?.message} {...register('address.city')} />
            <Input label="State" required autoComplete="address-level1" error={errors.address?.state?.message} {...register('address.state')} />
            <Input label="Postal code" required autoComplete="postal-code" error={errors.address?.postalCode?.message} {...register('address.postalCode')} />
            <Input label="Country" autoComplete="country-name" {...register('address.country')} />
          </CardBody>
        </Card>
        <StickyActions>
          <Button variant="outline" onClick={() => navigate(-1)}>
            Cancel
          </Button>
          <Button type="submit" loading={save.isPending}>
            {id ? 'Save changes' : 'Create property'}
          </Button>
        </StickyActions>
      </form>
    </div>
  );
}
