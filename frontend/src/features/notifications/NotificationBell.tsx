import { Link } from 'react-router-dom';
import { Bell } from 'lucide-react';
import { useUnreadCount } from './api';

export function NotificationBell() {
  const { data: unread = 0 } = useUnreadCount();
  const label = unread > 0 ? `Notifications, ${unread} unread` : 'Notifications';
  return (
    <Link
      to="/notifications"
      aria-label={label}
      title={label}
      className="relative inline-flex min-h-touch min-w-touch items-center justify-center rounded-full text-slate-600 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 dark:text-slate-300 dark:hover:bg-slate-800"
    >
      <Bell className="h-5 w-5" aria-hidden />
      {unread > 0 && (
        <span className="absolute right-1.5 top-1.5 inline-flex min-w-[18px] items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-semibold leading-[18px] text-white" aria-hidden>
          {unread > 99 ? '99+' : unread}
        </span>
      )}
    </Link>
  );
}
