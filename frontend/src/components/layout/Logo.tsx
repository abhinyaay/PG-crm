export function Logo({ className }: { className?: string }) {
  return (
    <span className={'inline-flex items-center gap-2 ' + (className ?? '')}>
      <svg viewBox="0 0 32 32" className="h-8 w-8" aria-hidden>
        <rect width="32" height="32" rx="8" className="fill-brand-600" />
        <path d="M8 15.5 16 9l8 6.5V24a1 1 0 0 1-1 1h-4.5v-5h-5v5H9a1 1 0 0 1-1-1z" fill="#fff" />
      </svg>
      <span className="text-base font-semibold tracking-tight text-slate-900 dark:text-white">
        Stay<span className="text-brand-600 dark:text-brand-400">Desk</span>
      </span>
    </span>
  );
}
