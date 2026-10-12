import { useEffect } from 'react';
import { useFieldArray, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useNavigate, useParams } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Card, CardBody, CardHeader } from '@/components/ui/Card';
import { Checkbox, Input, Select, Textarea } from '@/components/ui/Field';
import { PageHeader } from '@/components/ui/PageHeader';
import { Skeleton } from '@/components/ui/Skeleton';
import { StickyActions } from '@/components/ui/StickyActions';
import { useToast } from '@/components/ui/Toast';
import { PropertySelect } from '@/features/shared/PropertySelect';
import { useTenant } from '@/features/shared/lookups';
import { optionalEmail, optionalText, phone, PHONE_HINT } from '@/lib/validation';
import { useFormError } from '@/lib/useFormError';
import { useSaveTenant } from './api';
import { contactSchema } from './EmergencyContactsEditor';

const schema = z.object({
  propertyId: z.string().optional(),
  fullName: z.string().trim().min(2, 'Full name is required').max(150),
  email: optionalEmail,
  phone,
  gender: z.enum(['MALE', 'FEMALE', 'OTHER', '']).optional(),
  dateOfBirth: z.string().optional(),
  occupation: optionalText(100),
  organizationName: optionalText(150),
  permanentAddress: optionalText(1000),
  emergencyContacts: z.array(contactSchema),
});
type Values = z.infer<typeof schema>;
const FIELDS = ['propertyId', 'fullName', 'email', 'phone', 'gender', 'dateOfBirth', 'occupation', 'organizationName', 'permanentAddress', 'emergencyContacts'];

export default function TenantFormPage() {
  const params = useParams();
  const id = params.id ? Number(params.id) : undefined;
  const navigate = useNavigate();
  const toast = useToast();
  const onError = useFormError();
  const existing = useTenant(id);
  const save = useSaveTenant(id);
  const {
    register,
    control,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<Values>({
    resolver: zodResolver(id ? schema : schema.refine((v) => !!v.propertyId, { path: ['propertyId'], message: 'Select a property' })),
    defaultValues: { propertyId: '', fullName: '', phone: '', email: '', gender: '', emergencyContacts: [] },
  });
  const { fields, append, remove } = useFieldArray({ control, name: 'emergencyContacts' });

  useEffect(() => {
    if (existing.data) {
      const t = existing.data;
      reset({
        propertyId: String(t.propertyId),
        fullName: t.fullName,
        email: t.email ?? '',
        phone: t.phone,
        gender: t.gender ?? '',
        dateOfBirth: t.dateOfBirth ?? '',
        occupation: t.occupation ?? '',
        organizationName: t.organizationName ?? '',
        permanentAddress: t.permanentAddress ?? '',
        emergencyContacts: [],
      });
    }
  }, [existing.data, reset]);

  const submit = handleSubmit(async (v) => {
    const body = {
      fullName: v.fullName,
      email: v.email,
      phone: v.phone,
      gender: v.gender || undefined,
      dateOfBirth: v.dateOfBirth || undefined,
      occupation: v.occupation,
      organizationName: v.organizationName,
      permanentAddress: v.permanentAddress,
      ...(id ? {} : { propertyId: Number(v.propertyId), emergencyContacts: v.emergencyContacts.map((c) => ({ ...c, primary: !!c.primary })) }),
    };
    try {
      const t = await save.mutateAsync(body);
      toast.success(id ? 'Tenant updated' : 'Tenant registered');
      navigate(`/tenants/${t.id}`, { replace: true });
    } catch (e) {
      onError(e, setError, FIELDS);
    }
  });

  if (id && existing.isLoading) return <Skeleton className="h-96" />;

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader title={id ? 'Edit tenant' : 'Register tenant'} back={{ to: id ? `/tenants/${id}` : '/tenants', label: id ? 'Tenant' : 'Tenants' }} />
      <form onSubmit={submit} noValidate className="space-y-4">
        <Card>
          <CardHeader title="Profile" />
          <CardBody className="grid gap-4 sm:grid-cols-2">
            {!id && <PropertySelect allLabel={null} required containerClassName="sm:col-span-2" error={errors.propertyId?.message} {...register('propertyId')} />}
            <Input label="Full name" required autoComplete="name" error={errors.fullName?.message} {...register('fullName')} />
            <Input label="Phone" type="tel" inputMode="tel" required autoComplete="tel" hint={PHONE_HINT} error={errors.phone?.message} {...register('phone')} />
            <Input label="Email" type="email" autoComplete="email" error={errors.email?.message} {...register('email')} />
            <Select label="Gender" {...register('gender')}>
              <option value="">Prefer not to say</option>
              <option value="MALE">Male</option>
              <option value="FEMALE">Female</option>
              <option value="OTHER">Other</option>
            </Select>
            <Input label="Date of birth" type="date" error={errors.dateOfBirth?.message} {...register('dateOfBirth')} />
            <Input label="Occupation" error={errors.occupation?.message} {...register('occupation')} />
            <Input label="Company / college" containerClassName="sm:col-span-2" error={errors.organizationName?.message} {...register('organizationName')} />
            <Textarea label="Permanent address" containerClassName="sm:col-span-2" rows={2} error={errors.permanentAddress?.message} {...register('permanentAddress')} />
          </CardBody>
        </Card>
        {!id && (
          <Card>
            <CardHeader title="Emergency contacts" />
            <CardBody className="space-y-3">
              {fields.map((f, i) => (
                <fieldset key={f.id} className="grid gap-3 rounded-xl border border-slate-200 p-3 dark:border-slate-700 sm:grid-cols-2">
                  <legend className="px-1 text-xs font-medium text-slate-500">Contact {i + 1}</legend>
                  <Input label="Name" required error={errors.emergencyContacts?.[i]?.name?.message} {...register(`emergencyContacts.${i}.name`)} />
                  <Input label="Relationship" required error={errors.emergencyContacts?.[i]?.relationship?.message} {...register(`emergencyContacts.${i}.relationship`)} />
                  <Input label="Phone" type="tel" required error={errors.emergencyContacts?.[i]?.phone?.message} {...register(`emergencyContacts.${i}.phone`)} />
                  <Input label="Email" type="email" error={errors.emergencyContacts?.[i]?.email?.message} {...register(`emergencyContacts.${i}.email`)} />
                  <div className="flex items-center justify-between sm:col-span-2">
                    <Checkbox label="Primary" {...register(`emergencyContacts.${i}.primary`)} />
                    <Button variant="ghost" size="sm" onClick={() => remove(i)} icon={<Trash2 className="h-4 w-4" aria-hidden />}>
                      Remove
                    </Button>
                  </div>
                </fieldset>
              ))}
              <Button variant="outline" onClick={() => append({ name: '', relationship: '', phone: '', email: '', primary: fields.length === 0 })} icon={<Plus className="h-4 w-4" aria-hidden />}>
                Add contact
              </Button>
            </CardBody>
          </Card>
        )}
        <StickyActions>
          <Button variant="outline" onClick={() => navigate(-1)}>
            Cancel
          </Button>
          <Button type="submit" loading={save.isPending}>
            {id ? 'Save changes' : 'Register tenant'}
          </Button>
        </StickyActions>
      </form>
    </div>
  );
}
