import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Button } from './Button';
import { formatNumber } from '@/lib/format';

export function Pagination({ page, totalPages, totalElements, onPage, size }: { page: number; totalPages: number; totalElements: number; size: number; onPage: (p: number) => void }) {
  if (totalElements === 0) return null;
  const from = page * size + 1;
  const to = Math.min(totalElements, (page + 1) * size);
  return (
    <nav className="flex items-center justify-between gap-3 pt-4" aria-label="Pagination">
      <p className="text-sm text-slate-500 dark:text-slate-400">
        {formatNumber(from)}–{formatNumber(to)} of {formatNumber(totalElements)}
      </p>
      <div className="flex items-center gap-2">
        <Button variant="outline" size="icon" onClick={() => onPage(page - 1)} disabled={page <= 0} aria-label="Previous page">
          <ChevronLeft className="h-5 w-5" aria-hidden />
        </Button>
        <span className="text-sm tabular-nums text-slate-600 dark:text-slate-300">
          {page + 1} / {Math.max(totalPages, 1)}
        </span>
        <Button variant="outline" size="icon" onClick={() => onPage(page + 1)} disabled={page + 1 >= totalPages} aria-label="Next page">
          <ChevronRight className="h-5 w-5" aria-hidden />
        </Button>
      </div>
    </nav>
  );
}
