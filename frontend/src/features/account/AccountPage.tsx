import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { KeyRound, LogOut, MonitorSmartphone } from 'lucide-react';
import { ApiError, applyFieldErrors } from '@/api/errors';
import { useAuth, useUser } from '@/auth/AuthProvider';
import { Button } from '@/components/ui/Button';
import { Card, CardBody, CardHeader, DescriptionList } from '@/components/ui/Card';
import { Input } from '@/components/ui/Field';
import { PageHeader } from '@/components/ui/PageHeader';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/components/ui/Toast';
import { ROLE_LABELS } from '@/lib/roles';
import { PASSWORD_RULE, PASSWORD_HINT } from '@/lib/validation';

const schema = z
  .object({
    currentPassword: z.string().min(1, 'Enter your current password'),
    newPassword: z.string().regex(PASSWORD_RULE, PASSWORD_HINT),
    confirm: z.string(),
  })
  .refine((v) => v.newPassword === v.confirm, { message: 'Passwords do not match', path: ['confirm'] })
  .refine((v) => v.newPassword !== v.currentPassword, { message: 'New password must differ from the current one', path: ['newPassword'] });
type Values = z.infer<typeof schema>;

export default function AccountPage() {
  const user = useUser();
  const { changePassword, logout, logoutEverywhere } = useAuth();
  const toast = useToast();
  const [confirmAll, setConfirmAll] = useState(false);
  const [busyAll, setBusyAll] = useState(false);
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { currentPassword: '', newPassword: '', confirm: '' } });

  const submit = handleSubmit(async (v) => {
    try {
      await changePassword(v.currentPassword, v.newPassword);
      reset();
    } catch (e) {
      if (e instanceof ApiError && e.code === 'INVALID_CREDENTIALS') {
        setError('currentPassword', { message: 'Current password is incorrect' }, { shouldFocus: true });
        return;
      }
      if (e instanceof ApiError && e.code === 'PASSWORD_REUSED') {
        setError('newPassword', { message: e.message }, { shouldFocus: true });
        return;
      }
      const unmapped = applyFieldErrors(e, setError, ['currentPassword', 'newPassword']);
      if (!(e instanceof ApiError) || e.fieldErrors.length === 0 || unmapped.length) toast.apiError(e, 'Could not change password');
    }
  });

  return (
    <div className="mx-auto max-w-2xl space-y-4">
      <PageHeader title="Account & security" />
      <Card>
        <CardHeader title="Profile" />
        <CardBody>
          <DescriptionList
            items={[
              { label: 'Name', value: user.fullName },
              { label: 'Email', value: user.email },
              { label: 'Role', value: ROLE_LABELS[user.role] },
              { label: 'Organization', value: user.organizationId ?? 'Platform' },
            ]}
          />
        </CardBody>
      </Card>

      <Card>
        <CardHeader title="Change password" subtitle="You will be signed out on all devices afterwards." />
        <CardBody>
          <form onSubmit={submit} noValidate className="space-y-4">
            <input type="email" autoComplete="username" value={user.email} readOnly hidden />
            <Input label="Current password" type="password" autoComplete="current-password" required error={errors.currentPassword?.message} {...register('currentPassword')} />
            <Input label="New password" type="password" autoComplete="new-password" required hint={PASSWORD_HINT} error={errors.newPassword?.message} {...register('newPassword')} />
            <Input label="Confirm new password" type="password" autoComplete="new-password" required error={errors.confirm?.message} {...register('confirm')} />
            <div className="flex justify-end">
              <Button type="submit" loading={isSubmitting} icon={<KeyRound className="h-4 w-4" aria-hidden />} className="w-full sm:w-auto">
                Update password
              </Button>
            </div>
          </form>
        </CardBody>
      </Card>

      <Card>
        <CardHeader title="Sessions" />
        <CardBody className="flex flex-col gap-2 sm:flex-row">
          <Button variant="outline" onClick={() => void logout()} icon={<LogOut className="h-4 w-4" aria-hidden />}>
            Sign out
          </Button>
          <Button variant="outline" onClick={() => setConfirmAll(true)} icon={<MonitorSmartphone className="h-4 w-4" aria-hidden />}>
            Sign out of all devices
          </Button>
        </CardBody>
      </Card>

      <ConfirmDialog
        open={confirmAll}
        title="Sign out everywhere?"
        description="All your sessions on every device will end immediately."
        confirmLabel="Sign out everywhere"
        loading={busyAll}
        onClose={() => setConfirmAll(false)}
        onConfirm={async () => {
          setBusyAll(true);
          try {
            await logoutEverywhere();
          } catch (e) {
            toast.apiError(e);
          } finally {
            setBusyAll(false);
          }
        }}
      />
    </div>
  );
}
