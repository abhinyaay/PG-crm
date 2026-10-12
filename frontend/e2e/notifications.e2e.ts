import { expect, test } from './support/fixtures';
import { toast, topBar, uniqueSuffix } from './support/helpers';

test('notification bell shows the unread count and "Mark all read" clears it', async ({ openAs, apiAs }) => {
  // Arrange (API): a new maintenance ticket from the demo tenant notifies the property's manager.
  const tenantApi = await apiAs('tenant');
  const created = await tenantApi.post('/api/v1/maintenance', {
    data: { category: 'CLEANING', priority: 'LOW', title: `E2E notification check ${uniqueSuffix()}`, description: 'Created by the notifications e2e test.' },
  });
  expect(created.status(), await created.text()).toBe(201);
  const { ticketNumber } = (await created.json()) as { ticketNumber: string };

  const page = await openAs('manager');
  await page.goto('/dashboard');
  const bell = topBar(page).getByRole('link', { name: /^Notifications/ });
  await expect(bell).toHaveAccessibleName(/^Notifications, \d+ unread$/);
  const unread = Number((await bell.getAttribute('aria-label'))!.match(/(\d+) unread/)![1]);
  expect(unread).toBeGreaterThan(0);
  await expect(bell).toContainText(unread > 99 ? '99+' : String(unread));

  await bell.click();
  await expect(page).toHaveURL(/\/notifications$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Notifications' })).toBeVisible();
  await expect(page.getByText(/^\d+ unread$/)).toBeVisible();
  const item = page.getByRole('button', { name: new RegExp(`Maintenance ${ticketNumber}: OPEN`) });
  await expect(item).toBeVisible();
  await expect(item).toContainText('Unread.');

  await page.getByRole('tab', { name: /Unread/ }).click();
  await expect(page).toHaveURL(/filter=unread/);
  await expect(item).toBeVisible();

  await page.getByRole('button', { name: 'Mark all read' }).click();
  await expect(toast(page, 'All caught up')).toBeVisible();
  await expect(bell).toHaveAccessibleName('Notifications');
  await expect(bell).toHaveText('');
  await expect(page.getByText('0 unread')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Mark all read' })).toBeDisabled();
  await expect(page.getByText('No unread notifications')).toBeVisible();

  // still listed under "All", now as read
  await page.getByRole('tab', { name: /All/ }).click();
  await expect(item).toBeVisible();
  await expect(item).not.toContainText('Unread.');

  // the count survives a reload (server state, not just the cache)
  await page.reload();
  await expect(topBar(page).getByRole('link', { name: /^Notifications/ })).toHaveAccessibleName('Notifications');
});
