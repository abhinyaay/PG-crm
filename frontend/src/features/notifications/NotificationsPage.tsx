import { useNavigate } from 'react-router-dom';
import { Bell, CheckCheck } from 'lucide-react';
import { useUser } from '@/auth/AuthProvider';
import { Button } from '@/components/ui/Button';
import { PageHeader } from '@/components/ui/PageHeader';
import { Pagination } from '@/components/ui/Pagination';
import { SkeletonList } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { Tabs } from '@/components/ui/Tabs';
import { useToast } from '@/components/ui/Toast';
import { formatDateTime, formatRelative } from '@/lib/format';
import { useSearchState } from '@/lib/useSearchState';
import { cn } from '@/lib/cn';
import { notificationLink, useMarkAllRead, useMarkRead, useNotifications, useUnreadCount } from './api';

export default function NotificationsPage() {
  const user = useUser();
  const s = useSearchState();
  const navigate = useNavigate();
  const toast = useToast();
  const unreadOnly = s.get('filter') === 'unread';
  const list = useNotifications({ page: s.page, unreadOnly });
  const unread = useUnreadCount();
  const markRead = useMarkRead();
  const markAll = useMarkAllRead();

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader
        title="Notifications"
        subtitle={unread.data !== undefined ? `${unread.data} unread` : undefined}
        actions={
          <Button
            variant="outline"
            disabled={!unread.data}
            loading={markAll.isPending}
            onClick={async () => {
              try {
                await markAll.mutateAsync();
                toast.success('All caught up');
              } catch (e) {
                toast.apiError(e);
              }
            }}
            icon={<CheckCheck className="h-4 w-4" aria-hidden />}
          >
            Mark all read
          </Button>
        }
      />
      <Tabs
        className="mb-4"
        value={unreadOnly ? 'unread' : 'all'}
        onChange={(v) => s.set({ filter: v === 'unread' ? 'unread' : '' })}
        tabs={[
          { id: 'all', label: 'All' },
          { id: 'unread', label: 'Unread', count: unread.data },
        ]}
      />
      {list.isLoading ? (
        <SkeletonList />
      ) : list.error ? (
        <ErrorState error={list.error} onRetry={() => void list.refetch()} />
      ) : !list.data?.content.length ? (
        <EmptyState icon={<Bell className="h-6 w-6" aria-hidden />} title={unreadOnly ? 'No unread notifications' : 'No notifications yet'} />
      ) : (
        <>
          <ul className="space-y-2">
            {list.data.content.map((n) => {
              const link = notificationLink(n, user.role === 'TENANT');
              const isUnread = !n.readAt;
              return (
                <li key={n.id}>
                  <button
                    type="button"
                    onClick={() => {
                      if (isUnread) markRead.mutate(n.id);
                      if (link) navigate(link);
                    }}
                    className={cn(
                      'flex w-full items-start gap-3 rounded-2xl border p-4 text-left transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500',
                      isUnread ? 'border-brand-200 bg-brand-50/60 dark:border-brand-900 dark:bg-brand-950/30' : 'border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900',
                    )}
                  >
                    <span className={cn('mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full', isUnread ? 'bg-brand-600' : 'bg-transparent')} aria-hidden />
                    <span className="min-w-0 flex-1">
                      <span className="flex items-start justify-between gap-2">
                        <span className={cn('text-sm', isUnread ? 'font-semibold text-slate-900 dark:text-white' : 'font-medium text-slate-700 dark:text-slate-200')}>{n.title}</span>
                        <time className="shrink-0 text-xs text-slate-500" dateTime={n.createdAt} title={formatDateTime(n.createdAt)}>
                          {formatRelative(n.createdAt)}
                        </time>
                      </span>
                      <span className="mt-0.5 block text-sm text-slate-600 dark:text-slate-300">{n.body}</span>
                      {isUnread && <span className="sr-only">Unread.</span>}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
          <Pagination page={list.data.page} size={list.data.size} totalPages={list.data.totalPages} totalElements={list.data.totalElements} onPage={(p) => s.set({ page: p })} />
        </>
      )}
    </div>
  );
}
