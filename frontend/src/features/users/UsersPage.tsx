import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { KeyRound, Plus, Power, PowerOff } from 'lucide-react';
import type { Role, UserResponse } from '@/api/types';
import { useUser } from '@/auth/AuthProvider';
import { Button } from '@/components/ui/Button';
import { Checkbox, Input, Select } from '@/components/ui/Field';
import { Modal } from '@/components/ui/Modal';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { PageHeader } from '@/components/ui/PageHeader';
import { Pagination } from '@/components/ui/Pagination';
import { ResponsiveTable, type Column } from '@/components/ui/ResponsiveTable';
import { SearchInput } from '@/components/ui/SearchInput';
import { SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { useToast } from '@/components/ui/Toast';
import { usePropertyOptions } from '@/features/shared/lookups';
import { formatDateTime } from '@/lib/format';
import { ROLE_LABELS } from '@/lib/roles';
import { useDebounce } from '@/lib/useDebounce';
import { useFormError } from '@/lib/useFormError';
import { useSearchState } from '@/lib/useSearchState';
import { PASSWORD_HINT, PASSWORD_RULE, PHONE_HINT, PHONE_RULE } from '@/lib/validation';
import { useUserActions, useUsers } from './api';

const CREATABLE: Role[] = ['PROPERTY_ADMIN', 'MANAGER', 'STAFF'];

const schema = z.object({
  fullName: z.string().trim().min(2, 'Name is required').max(150),
  email: z.string().trim().email('Enter a valid email'),
  phone: z
    .string()
    .trim()
    .optional()
    .refine((v) => !v || PHONE_RULE.test(v), PHONE_HINT),
  role: z.enum(['SUPER_ADMIN', 'PROPERTY_ADMIN', 'MANAGER', 'STAFF', 'TENANT']),
  password: z.string().regex(PASSWORD_RULE, PASSWORD_HINT),
  organizationId: z.string().optional(),
  propertyIds: z.array(z.string()),
});
type Values = z.infer<typeof schema>;

function CreateUserDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const me = useUser();
  const toast = useToast();
  const onError = useFormError();
  const { create } = useUserActions();
  const properties = usePropertyOptions();
  const roles: Role[] = me.role === 'SUPER_ADMIN' ? ['SUPER_ADMIN', ...CREATABLE] : CREATABLE;
  const {
    register,
    handleSubmit,
    watch,
    reset,
    setError,
    formState: { errors },
  } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { fullName: '', email: '', phone: '', role: 'STAFF', password: '', propertyIds: [] } });
  const role = watch('role');
  const needsProperties = role === 'MANAGER' || role === 'STAFF';

  const submit = handleSubmit(async (v) => {
    try {
      const u = await create.mutateAsync({
        fullName: v.fullName,
        email: v.email,
        phone: v.phone || undefined,
        role: v.role,
        password: v.password,
        organizationId: me.role === 'SUPER_ADMIN' && v.organizationId ? Number(v.organizationId) : undefined,
        propertyIds: needsProperties ? v.propertyIds.map(Number) : undefined,
      });
      toast.success('User created', `${u.fullName} can now sign in.`);
      reset();
      onClose();
    } catch (e) {
      onError(e, setError, ['fullName', 'email', 'phone', 'role', 'password', 'organizationId', 'propertyIds'], 'Could not create user');
    }
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      dismissible={!create.isPending}
      size="lg"
      title="New user"
      description="Residents get portal accounts from their tenant profile."
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button loading={create.isPending} onClick={() => void submit()}>
            Create user
          </Button>
        </>
      }
    >
      <form onSubmit={submit} noValidate className="grid gap-3 sm:grid-cols-2">
        <Input label="Full name" required error={errors.fullName?.message} {...register('fullName')} />
        <Input label="Email" type="email" autoComplete="off" required error={errors.email?.message} {...register('email')} />
        <Input label="Phone" type="tel" error={errors.phone?.message} {...register('phone')} />
        <Select label="Role" required {...register('role')}>
          {roles.map((r) => (
            <option key={r} value={r}>
              {ROLE_LABELS[r]}
            </option>
          ))}
        </Select>
        <Input label="Initial password" type="password" autoComplete="new-password" required hint={PASSWORD_HINT} error={errors.password?.message} containerClassName="sm:col-span-2" {...register('password')} />
        {me.role === 'SUPER_ADMIN' && <Input label="Organization id" type="number" {...register('organizationId')} />}
        {needsProperties && (
          <fieldset className="sm:col-span-2">
            <legend className="mb-1 text-sm font-medium text-slate-700 dark:text-slate-300">Assign to properties</legend>
            <div className="grid gap-x-4 sm:grid-cols-2">
              {properties.data?.map((p) => <Checkbox key={p.id} label={p.name} value={String(p.id)} {...register('propertyIds')} />)}
            </div>
            {errors.propertyIds && <p className="text-sm text-red-600">{errors.propertyIds.message}</p>}
          </fieldset>
        )}
      </form>
    </Modal>
  );
}

export default function UsersPage() {
  const me = useUser();
  const toast = useToast();
  const s = useSearchState();
  const [q, setQ] = useState(s.get('q'));
  const dq = useDebounce(q);
  const list = useUsers({ page: s.page, q: dq || undefined, role: s.get('role') || undefined, status: s.get('status') || undefined });
  const actions = useUserActions();
  const [createOpen, setCreateOpen] = useState(false);
  const [toggle, setToggle] = useState<UserResponse | null>(null);
  const [resetFor, setResetFor] = useState<UserResponse | null>(null);
  const [newPassword, setNewPassword] = useState('');

  const columns: Column<UserResponse>[] = [
    {
      key: 'name',
      header: 'User',
      primary: true,
      cell: (u) => (
        <span className="block min-w-0">
          <span className="block truncate">{u.fullName}</span>
          <span className="block truncate text-xs font-normal text-slate-500">{u.email}</span>
        </span>
      ),
    },
    { key: 'role', header: 'Role', cell: (u) => ROLE_LABELS[u.role] },
    { key: 'status', header: 'Status', hideOnMobile: true, cell: (u) => <StatusBadge status={u.status} /> },
    { key: 'last', header: 'Last sign-in', cell: (u) => formatDateTime(u.lastLoginAt) },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      cell: (u) =>
        u.id === me.id ? (
          <span className="text-xs text-slate-500">You</span>
        ) : (
          <span className="flex gap-1">
            <Button size="sm" variant="ghost" onClick={() => setToggle(u)} icon={u.status === 'ACTIVE' ? <PowerOff className="h-4 w-4" aria-hidden /> : <Power className="h-4 w-4" aria-hidden />}>
              {u.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
            </Button>
            <Button size="sm" variant="ghost" onClick={() => { setNewPassword(''); setResetFor(u); }} icon={<KeyRound className="h-4 w-4" aria-hidden />}>
              Reset
            </Button>
          </span>
        ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="Users"
        subtitle={list.data ? `${list.data.totalElements} accounts` : undefined}
        actions={
          <Button onClick={() => setCreateOpen(true)} icon={<Plus className="h-4 w-4" aria-hidden />}>
            New user
          </Button>
        }
      />
      <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-[1fr_12rem_12rem]">
        <SearchInput className="col-span-2 md:col-span-1" value={q} onChange={(v) => { setQ(v); s.set({ q: v }); }} placeholder="Search name or email" label="Search users" />
        <Select aria-label="Role" value={s.get('role')} onChange={(e) => s.set({ role: e.target.value })}>
          <option value="">All roles</option>
          {(['SUPER_ADMIN', 'PROPERTY_ADMIN', 'MANAGER', 'STAFF', 'TENANT'] as Role[]).map((r) => (
            <option key={r} value={r}>
              {ROLE_LABELS[r]}
            </option>
          ))}
        </Select>
        <Select aria-label="Status" value={s.get('status')} onChange={(e) => s.set({ status: e.target.value })}>
          <option value="">Any status</option>
          <option value="ACTIVE">Active</option>
          <option value="INACTIVE">Inactive</option>
        </Select>
      </div>
      {list.isLoading ? (
        <SkeletonList />
      ) : list.error ? (
        <ErrorState error={list.error} onRetry={() => void list.refetch()} />
      ) : !list.data?.content.length ? (
        <EmptyState title="No users found" />
      ) : (
        <>
          <ResponsiveTable rows={list.data.content} columns={columns} rowKey={(u) => u.id} mobileBadge={(u) => <StatusBadge status={u.status} />} caption="Users" />
          <Pagination page={list.data.page} size={list.data.size} totalPages={list.data.totalPages} totalElements={list.data.totalElements} onPage={(p) => s.set({ page: p })} />
        </>
      )}
      <CreateUserDialog open={createOpen} onClose={() => setCreateOpen(false)} />
      <ConfirmDialog
        open={!!toggle}
        title={toggle?.status === 'ACTIVE' ? `Deactivate ${toggle?.fullName}?` : `Activate ${toggle?.fullName}?`}
        description={toggle?.status === 'ACTIVE' ? 'They are signed out everywhere immediately and cannot sign in.' : 'They will be able to sign in again.'}
        confirmLabel={toggle?.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
        tone={toggle?.status === 'ACTIVE' ? 'danger' : 'primary'}
        loading={actions.activate.isPending || actions.deactivate.isPending}
        onClose={() => setToggle(null)}
        onConfirm={async () => {
          try {
            if (toggle!.status === 'ACTIVE') await actions.deactivate.mutateAsync(toggle!.id);
            else await actions.activate.mutateAsync(toggle!.id);
            toast.success('User updated');
            setToggle(null);
          } catch (e) {
            toast.apiError(e);
          }
        }}
      />
      <Modal
        open={!!resetFor}
        onClose={() => setResetFor(null)}
        size="sm"
        title={`Reset password for ${resetFor?.fullName ?? ''}`}
        description="Their existing sessions are ended."
        footer={
          <>
            <Button variant="outline" onClick={() => setResetFor(null)}>
              Cancel
            </Button>
            <Button
              disabled={!PASSWORD_RULE.test(newPassword)}
              loading={actions.resetPassword.isPending}
              onClick={async () => {
                try {
                  await actions.resetPassword.mutateAsync({ id: resetFor!.id, newPassword });
                  toast.success('Password reset');
                  setResetFor(null);
                } catch (e) {
                  toast.apiError(e);
                }
              }}
            >
              Reset password
            </Button>
          </>
        }
      >
        <Input label="New password" type="password" autoComplete="new-password" required hint={PASSWORD_HINT} value={newPassword} onChange={(e) => setNewPassword(e.target.value)} data-autofocus />
      </Modal>
    </div>
  );
}
