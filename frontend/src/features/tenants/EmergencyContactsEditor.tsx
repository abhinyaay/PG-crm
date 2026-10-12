import { useEffect } from 'react';
import { useFieldArray, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Plus, Trash2 } from 'lucide-react';
import type { EmergencyContact } from '@/api/types';
import { Button } from '@/components/ui/Button';
import { Checkbox, Input } from '@/components/ui/Field';
import { useToast } from '@/components/ui/Toast';
import { optionalEmail, phone } from '@/lib/validation';
import { useFormError } from '@/lib/useFormError';
import { useSaveContacts } from './api';

export const contactSchema = z.object({
  name: z.string().trim().min(1, 'Name is required').max(150),
  relationship: z.string().trim().min(1, 'Relationship is required').max(50),
  phone,
  email: optionalEmail,
  primary: z.boolean().optional(),
});

const schema = z.object({ contacts: z.array(contactSchema) });
type Values = z.infer<typeof schema>;

export function EmergencyContactsEditor({ tenantId, contacts, onDone }: { tenantId: number; contacts: EmergencyContact[]; onDone?: () => void }) {
  const toast = useToast();
  const onError = useFormError();
  const save = useSaveContacts(tenantId);
  const { control, register, handleSubmit, reset, setError, formState: { errors, isDirty } } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { contacts } });
  const { fields, append, remove } = useFieldArray({ control, name: 'contacts' });
  useEffect(() => reset({ contacts }), [contacts, reset]);

  const submit = handleSubmit(async (v) => {
    try {
      await save.mutateAsync(v.contacts.map((c) => ({ ...c, email: c.email || undefined, primary: !!c.primary })));
      toast.success('Emergency contacts saved');
      onDone?.();
    } catch (e) {
      onError(e, setError, ['contacts']);
    }
  });

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      {fields.length === 0 && <p className="text-sm text-slate-500">No emergency contacts yet.</p>}
      {fields.map((f, i) => (
        <fieldset key={f.id} className="grid gap-3 rounded-xl border border-slate-200 p-3 dark:border-slate-700 sm:grid-cols-2">
          <legend className="px-1 text-xs font-medium text-slate-500">Contact {i + 1}</legend>
          <Input label="Name" required error={errors.contacts?.[i]?.name?.message} {...register(`contacts.${i}.name`)} />
          <Input label="Relationship" required error={errors.contacts?.[i]?.relationship?.message} {...register(`contacts.${i}.relationship`)} />
          <Input label="Phone" type="tel" inputMode="tel" required error={errors.contacts?.[i]?.phone?.message} {...register(`contacts.${i}.phone`)} />
          <Input label="Email" type="email" error={errors.contacts?.[i]?.email?.message} {...register(`contacts.${i}.email`)} />
          <div className="flex items-center justify-between sm:col-span-2">
            <Checkbox label="Primary contact" {...register(`contacts.${i}.primary`)} />
            <Button variant="ghost" size="sm" onClick={() => remove(i)} icon={<Trash2 className="h-4 w-4" aria-hidden />}>
              Remove
            </Button>
          </div>
        </fieldset>
      ))}
      <div className="flex flex-col gap-2 sm:flex-row sm:justify-between">
        <Button variant="outline" onClick={() => append({ name: '', relationship: '', phone: '', email: '', primary: fields.length === 0 })} icon={<Plus className="h-4 w-4" aria-hidden />}>
          Add contact
        </Button>
        <Button type="submit" loading={save.isPending} disabled={!isDirty}>
          Save contacts
        </Button>
      </div>
    </form>
  );
}
