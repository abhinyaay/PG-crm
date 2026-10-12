import { useState } from 'react';
import { FileDown } from 'lucide-react';
import { Button, type ButtonProps } from '@/components/ui/Button';
import { useToast } from '@/components/ui/Toast';
import { openBlob } from '@/lib/download';
import { fetchReceipt } from './api';

/**
 * Opens the PDF receipt. The receipt endpoint needs the bearer token, so a plain <a href> cannot be used: we fetch
 * it as a blob and open an object URL (a tab is opened synchronously on click so pop-up blockers allow it).
 */
export function ReceiptButton({ paymentId, receiptNumber, label = 'Receipt', ...rest }: { paymentId: number; receiptNumber?: string; label?: string } & Omit<ButtonProps, 'onClick'>) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  return (
    <Button
      variant="outline"
      size="sm"
      loading={busy}
      icon={<FileDown className="h-4 w-4" aria-hidden />}
      onClick={async (e) => {
        e.stopPropagation();
        const isStandalone = window.matchMedia?.('(display-mode: standalone)').matches;
        const tab = isStandalone ? null : window.open('', '_blank');
        if (tab) tab.document.title = 'Loading receipt…';
        setBusy(true);
        try {
          const blob = await fetchReceipt(paymentId);
          openBlob(blob, `${receiptNumber ?? `receipt-${paymentId}`}.pdf`, tab);
        } catch (err) {
          tab?.close();
          toast.apiError(err, 'Could not load receipt');
        } finally {
          setBusy(false);
        }
      }}
      {...rest}
    >
      {label}
    </Button>
  );
}
