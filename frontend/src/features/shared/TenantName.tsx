import { Link } from 'react-router-dom';
import { useAuth } from '@/auth/AuthProvider';
import { isStaffOrAbove } from '@/lib/roles';
import { useTenant } from './lookups';

/** Resolves a tenant id to a name (cached per tenant) and links to the profile for staff roles. */
export function TenantName({ id, link = true }: { id: number | null | undefined; link?: boolean }) {
  const { user } = useAuth();
  const canSee = isStaffOrAbove(user?.role);
  const { data, isLoading } = useTenant(canSee ? id : null);
  if (id == null) return <span>—</span>;
  const label = data?.fullName ?? (isLoading ? '…' : `Tenant #${id}`);
  if (!link || !canSee) return <span>{label}</span>;
  return (
    <Link to={`/tenants/${id}`} className="font-medium text-brand-700 hover:underline dark:text-brand-300" onClick={(e) => e.stopPropagation()}>
      {label}
    </Link>
  );
}
