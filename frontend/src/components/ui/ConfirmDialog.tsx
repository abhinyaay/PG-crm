import { useState, type ReactNode } from 'react';
import { Modal } from './Modal';
import { Button } from './Button';
import { Textarea } from './Field';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  description?: ReactNode;
  confirmLabel?: string;
  tone?: 'danger' | 'primary';
  loading?: boolean;
  /** Ask for a reason (required by void/cancel endpoints). */
  reasonLabel?: string;
  reasonRequired?: boolean;
  onConfirm: (reason: string) => void;
  onClose: () => void;
}

export function ConfirmDialog({ open, title, description, confirmLabel = 'Confirm', tone = 'danger', loading, reasonLabel, reasonRequired, onConfirm, onClose }: ConfirmDialogProps) {
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const missing = !!reasonLabel && !!reasonRequired && reason.trim() === '';
  const close = () => {
    setReason('');
    setTouched(false);
    onClose();
  };
  return (
    <Modal
      open={open}
      onClose={close}
      title={title}
      size="sm"
      dismissible={!loading}
      footer={
        <>
          <Button variant="outline" onClick={close} disabled={loading}>
            Cancel
          </Button>
          <Button
            variant={tone === 'danger' ? 'danger' : 'primary'}
            loading={loading}
            onClick={() => {
              setTouched(true);
              if (!missing) onConfirm(reason.trim());
            }}
          >
            {confirmLabel}
          </Button>
        </>
      }
    >
      {description && <div className="text-sm text-slate-600 dark:text-slate-300">{description}</div>}
      {reasonLabel && (
        <Textarea
          containerClassName="mt-4"
          label={reasonLabel}
          required={reasonRequired}
          value={reason}
          maxLength={255}
          onChange={(e) => setReason(e.target.value)}
          error={touched && missing ? 'A reason is required' : undefined}
          data-autofocus
        />
      )}
    </Modal>
  );
}
