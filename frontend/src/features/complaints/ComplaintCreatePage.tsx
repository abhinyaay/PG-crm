import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useNavigate } from 'react-router-dom';
import { COMPLAINT_CATEGORIES, PRIORITIES } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { Button } from '@/components/ui/Button';
import { Card, CardBody } from '@/components/ui/Card';
import { Input, Select, Textarea } from '@/components/ui/Field';
import { PageHeader } from '@/components/ui/PageHeader';
import { StickyActions } from '@/components/ui/StickyActions';
import { useToast } from '@/components/ui/Toast';
import { PropertySelect } from '@/features/shared/PropertySelect';
import { humanize } from '@/lib/format';
import { useFormError } from '@/lib/useFormError';
import { useCreateComplaint } from './api';

const schema = z.object({
  propertyId: z.string().optional(),
  category: z.enum(COMPLAINT_CATEGORIES),
  priority: z.enum(PRIORITIES),
  subject: z.string().trim().min(3, 'Add a short subject').max(150),
  description: z.string().trim().min(5, 'Describe the issue').max(4000),
});
type Values = z.infer<typeof schema>;

export default function ComplaintCreatePage() {
  const user = useUser();
  const isTenant = user.role === 'TENANT';
  const navigate = useNavigate();
  const toast = useToast();
  const onError = useFormError();
  const create = useCreateComplaint();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<Values>({
    resolver: zodResolver(isTenant ? schema : schema.refine((v) => !!v.propertyId, { path: ['propertyId'], message: 'Select a property' })),
    defaultValues: { category: 'CLEANLINESS', priority: 'MEDIUM', subject: '', description: '', propertyId: '' },
  });

  const submit = handleSubmit(async (v) => {
    try {
      const c = await create.mutateAsync({ propertyId: isTenant ? undefined : Number(v.propertyId), category: v.category, priority: v.priority, subject: v.subject, description: v.description });
      toast.success('Complaint registered', c.complaintNumber);
      navigate(`/complaints/${c.id}`, { replace: true });
    } catch (e) {
      onError(e, setError, ['propertyId', 'category', 'priority', 'subject', 'description'], 'Could not register complaint');
    }
  });

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader title="New complaint" back={{ to: '/complaints', label: 'Complaints' }} />
      <form onSubmit={submit} noValidate>
        <Card>
          <CardBody className="space-y-4">
            {!isTenant && <PropertySelect allLabel={null} required error={errors.propertyId?.message} {...register('propertyId')} />}
            <div className="grid grid-cols-2 gap-4">
              <Select label="Category" required {...register('category')}>
                {COMPLAINT_CATEGORIES.map((c) => (
                  <option key={c} value={c}>
                    {humanize(c)}
                  </option>
                ))}
              </Select>
              <Select label="Priority" required {...register('priority')}>
                {PRIORITIES.map((c) => (
                  <option key={c} value={c}>
                    {humanize(c)}
                  </option>
                ))}
              </Select>
            </div>
            <Input label="Subject" required maxLength={150} error={errors.subject?.message} {...register('subject')} />
            <Textarea label="Description" required rows={5} maxLength={4000} error={errors.description?.message} {...register('description')} />
          </CardBody>
        </Card>
        <StickyActions>
          <Button variant="outline" onClick={() => navigate(-1)}>
            Cancel
          </Button>
          <Button type="submit" loading={create.isPending}>
            Submit complaint
          </Button>
        </StickyActions>
      </form>
    </div>
  );
}
