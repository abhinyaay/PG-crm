import type { ReactNode } from 'react';

/**
 * Primary actions that stay reachable with the thumb: sticky above the bottom tab bar on phones,
 * an ordinary right-aligned row on larger screens.
 */
export function StickyActions({ children }: { children: ReactNode }) {
  return (
    <div className="sticky bottom-[calc(4rem+env(safe-area-inset-bottom))] z-20 -mx-4 mt-6 border-t border-slate-200 bg-white/95 px-4 py-3 backdrop-blur dark:border-slate-800 dark:bg-slate-950/95 md:static md:mx-0 md:border-0 md:bg-transparent md:px-0 md:py-0 md:backdrop-blur-none">
      <div className="flex gap-2 [&>*]:flex-1 sm:justify-end sm:[&>*]:flex-none">{children}</div>
    </div>
  );
}
