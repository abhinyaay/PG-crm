import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { Eye, EyeOff, LogIn } from 'lucide-react';
import { ApiError, applyFieldErrors } from '@/api/errors';
import { useAuth } from '@/auth/AuthProvider';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Field';
import { Logo } from '@/components/layout/Logo';
import { homePath } from '@/lib/roles';

export const loginSchema = z.object({
  email: z.string().trim().min(1, 'Email is required').email('Enter a valid email address'),
  password: z.string().min(1, 'Password is required').max(72, 'Password is too long'),
});
type LoginValues = z.infer<typeof loginSchema>;

export function LoginForm({ onSubmit }: { onSubmit: (values: LoginValues) => Promise<void> }) {
  const [showPassword, setShowPassword] = useState(false);
  const [formError, setFormError] = useState<{ message: string; correlationId?: string } | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({ resolver: zodResolver(loginSchema), defaultValues: { email: '', password: '' } });

  const submit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      await onSubmit(values);
    } catch (e) {
      applyFieldErrors(e, setError, ['email', 'password']);
      if (e instanceof ApiError) {
        setFormError({
          message: e.status === 401 ? 'Incorrect email or password.' : e.status === 429 ? 'Too many sign-in attempts. Please wait a minute and try again.' : e.message,
          correlationId: e.correlationId,
        });
      } else {
        setFormError({ message: 'Could not sign in. Please try again.' });
      }
    }
  });

  return (
    <form onSubmit={submit} noValidate className="space-y-4" aria-label="Sign in">
      {formError && (
        <div role="alert" className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/50 dark:text-red-300">
          {formError.message}
          {formError.correlationId && <span className="mt-1 block font-mono text-xs opacity-75">Ref: {formError.correlationId}</span>}
        </div>
      )}
      <Input label="Email" type="email" autoComplete="username" inputMode="email" required error={errors.email?.message} {...register('email')} />
      <div className="relative">
        <Input
          label="Password"
          type={showPassword ? 'text' : 'password'}
          autoComplete="current-password"
          required
          error={errors.password?.message}
          className="pr-12"
          {...register('password')}
        />
        <button
          type="button"
          onClick={() => setShowPassword((s) => !s)}
          className="absolute right-1 top-[30px] inline-flex min-h-touch min-w-touch items-center justify-center rounded-lg text-slate-500 hover:text-slate-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 dark:hover:text-slate-200"
          aria-label={showPassword ? 'Hide password' : 'Show password'}
        >
          {showPassword ? <EyeOff className="h-5 w-5" aria-hidden /> : <Eye className="h-5 w-5" aria-hidden />}
        </button>
      </div>
      <Button type="submit" size="lg" className="w-full" loading={isSubmitting} icon={<LogIn className="h-5 w-5" aria-hidden />}>
        Sign in
      </Button>
    </form>
  );
}

export default function LoginPage() {
  const { status, user, login, signOutReason } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = params.get('next');

  if (status === 'authenticated' && user) {
    return <Navigate to={next && next.startsWith('/') && !next.startsWith('//') ? next : homePath(user.role)} replace />;
  }

  return (
    <div className="flex min-h-dvh flex-col bg-gradient-to-b from-brand-50 to-white px-4 py-8 dark:from-slate-950 dark:to-slate-900 sm:justify-center">
      <div className="mx-auto w-full max-w-sm">
        <div className="mb-8 flex justify-center">
          <Logo />
        </div>
        <div className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
          <h1 className="text-xl font-semibold text-slate-900 dark:text-white">Sign in</h1>
          <p className="mb-5 mt-1 text-sm text-slate-500 dark:text-slate-400">Manage your properties, residents and rent — or check your stay.</p>
          {signOutReason && (
            <p className="mb-4 rounded-xl bg-amber-50 p-3 text-sm text-amber-800 dark:bg-amber-950/50 dark:text-amber-300" role="status">
              {signOutReason}
            </p>
          )}
          <LoginForm
            onSubmit={async ({ email, password }) => {
              const u = await login(email, password);
              const safeNext = next && next.startsWith('/') && !next.startsWith('//') ? next : null;
              navigate(safeNext ?? homePath(u.role), { replace: true });
            }}
          />
        </div>
        <p className="mt-6 text-center text-xs text-slate-500 dark:text-slate-400">Forgot your password? Ask your property admin to reset it.</p>
      </div>
    </div>
  );
}
