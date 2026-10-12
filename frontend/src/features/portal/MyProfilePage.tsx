import { Card, CardBody, CardHeader, DescriptionList } from '@/components/ui/Card';
import { PageHeader } from '@/components/ui/PageHeader';
import { SkeletonCards } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { useMyTenant } from '@/features/tenants/api';
import { EmergencyContactsEditor } from '@/features/tenants/EmergencyContactsEditor';
import { DocumentsSection } from '@/features/tenants/DocumentsSection';
import { formatDate, humanize } from '@/lib/format';

export default function MyProfilePage() {
  const q = useMyTenant();
  if (q.isLoading) return <SkeletonCards />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const t = q.data;
  return (
    <div className="mx-auto max-w-3xl space-y-4">
      <PageHeader title="My profile" subtitle="Contact the front desk to change your personal details." />
      <Card>
        <CardHeader title={t.fullName} actions={<StatusBadge status={t.status} />} />
        <CardBody>
          <DescriptionList
            items={[
              { label: 'Email', value: t.email },
              { label: 'Phone', value: t.phone },
              { label: 'Gender', value: humanize(t.gender) },
              { label: 'Date of birth', value: formatDate(t.dateOfBirth) },
              { label: 'Occupation', value: t.occupation },
              { label: 'Company / college', value: t.organizationName },
              { label: 'Joined', value: formatDate(t.joiningDate) },
              { label: 'Permanent address', value: t.permanentAddress, full: true },
            ]}
          />
        </CardBody>
      </Card>
      <Card>
        <CardHeader title="Emergency contacts" subtitle="Who should we call if something happens?" />
        <CardBody>
          <EmergencyContactsEditor tenantId={t.id} contacts={t.emergencyContacts ?? []} />
        </CardBody>
      </Card>
      <Card>
        <CardHeader title="KYC documents" subtitle="Numbers are masked for your privacy." />
        <CardBody>
          <DocumentsSection tenantId={t.id} canManage={false} />
        </CardBody>
      </Card>
    </div>
  );
}
