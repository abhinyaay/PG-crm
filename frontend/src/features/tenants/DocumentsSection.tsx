import { useState } from 'react';
import { FileCheck2, Plus, ShieldCheck } from 'lucide-react';
import { DOCUMENT_TYPES, type DocumentType, type VerificationStatus } from '@/api/types';
import { Button } from '@/components/ui/Button';
import { Input, Select } from '@/components/ui/Field';
import { Modal } from '@/components/ui/Modal';
import { SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusBadge } from '@/components/ui/Badge';
import { useToast } from '@/components/ui/Toast';
import { formatDate, humanize } from '@/lib/format';
import { useAddDocument, useReviewDocument, useTenantDocuments } from './api';

/** KYC document metadata. Numbers are only ever shown masked (the API never returns the full number). */
export function DocumentsSection({ tenantId, canManage }: { tenantId: number; canManage: boolean }) {
  const toast = useToast();
  const docs = useTenantDocuments(tenantId);
  const add = useAddDocument(tenantId);
  const review = useReviewDocument(tenantId);
  const [open, setOpen] = useState(false);
  const [type, setType] = useState<DocumentType>('AADHAAR');
  const [number, setNumber] = useState('');
  const valid = /^[A-Za-z0-9 -]{4,30}$/.test(number);

  return (
    <div className="space-y-3">
      {canManage && (
        <div className="flex justify-end">
          <Button variant="outline" size="sm" onClick={() => setOpen(true)} icon={<Plus className="h-4 w-4" aria-hidden />}>
            Add document
          </Button>
        </div>
      )}
      {docs.isLoading ? (
        <SkeletonList rows={2} />
      ) : docs.error ? (
        <ErrorState error={docs.error} onRetry={() => void docs.refetch()} />
      ) : !docs.data?.length ? (
        <EmptyState icon={<FileCheck2 className="h-6 w-6" aria-hidden />} title="No KYC documents" />
      ) : (
        <ul className="divide-y divide-slate-100 rounded-xl border border-slate-200 dark:divide-slate-800 dark:border-slate-700">
          {docs.data.map((d) => (
            <li key={d.id} className="flex flex-wrap items-center justify-between gap-3 p-3">
              <div className="min-w-0">
                <p className="font-medium text-slate-900 dark:text-slate-100">{humanize(d.documentType)}</p>
                <p className="font-mono text-sm tracking-wider text-slate-600 dark:text-slate-300" aria-label="Masked document number">
                  {d.documentNumberMasked}
                </p>
                <p className="text-xs text-slate-500">Added {formatDate(d.createdAt)}{d.verifiedAt ? ` · reviewed ${formatDate(d.verifiedAt)}` : ''}</p>
              </div>
              <div className="flex items-center gap-2">
                <StatusBadge status={d.verificationStatus} />
                {canManage && (
                  <Select
                    aria-label={`Review ${humanize(d.documentType)}`}
                    value={d.verificationStatus}
                    disabled={review.isPending}
                    className="md:min-h-[36px] md:py-1"
                    onChange={async (e) => {
                      try {
                        await review.mutateAsync({ documentId: d.id, status: e.target.value as VerificationStatus });
                        toast.success('Document reviewed');
                      } catch (err) {
                        toast.apiError(err, 'Could not update document');
                      }
                    }}
                  >
                    <option value="PENDING">Pending</option>
                    <option value="VERIFIED">Verified</option>
                    <option value="REJECTED">Rejected</option>
                  </Select>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title="Add KYC document"
        description="Only a masked number is stored and displayed."
        size="sm"
        footer={
          <>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              disabled={!valid}
              loading={add.isPending}
              icon={<ShieldCheck className="h-4 w-4" aria-hidden />}
              onClick={async () => {
                try {
                  await add.mutateAsync({ documentType: type, documentNumber: number.trim() });
                  toast.success('Document added');
                  setNumber('');
                  setOpen(false);
                } catch (e) {
                  toast.apiError(e, 'Could not add document');
                }
              }}
            >
              Add
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <Select label="Document type" value={type} onChange={(e) => setType(e.target.value as DocumentType)}>
            {DOCUMENT_TYPES.map((t) => (
              <option key={t} value={t}>
                {humanize(t)}
              </option>
            ))}
          </Select>
          <Input label="Document number" required autoComplete="off" value={number} onChange={(e) => setNumber(e.target.value)} hint="4–30 letters, digits, spaces or -" error={number && !valid ? 'Invalid document number' : undefined} />
        </div>
      </Modal>
    </div>
  );
}
