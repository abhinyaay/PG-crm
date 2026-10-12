import type { Page } from '@playwright/test';
import { ACCOUNTS } from './support/env';
import { expect, test } from './support/fixtures';
import { field, idFromUrl, pageSubtitle, toast, uniqueSuffix } from './support/helpers';

/**
 * Maintenance workflow across three roles:
 * tenant raises -> manager assigns to staff -> staff starts and resolves with notes -> tenant closes.
 */
test('maintenance request flows from tenant to staff and back, with a full timeline', async ({ openAs }) => {
  test.setTimeout(120_000);
  const title = `E2E leaking tap ${uniqueSuffix()}`;
  const notes = `Replaced the washer and tested for 10 minutes (${uniqueSuffix()})`;
  let path = '';
  const historyList = (page: Page) => page.getByRole('list').filter({ has: page.getByRole('listitem').filter({ hasText: /^Open/ }) });

  const tenant = await openAs('tenant');
  await test.step('tenant raises a request', async () => {
    await tenant.goto('/maintenance');
    await expect(tenant.getByRole('heading', { level: 1, name: 'My maintenance requests' })).toBeVisible();
    await tenant.getByRole('link', { name: 'New request' }).click();
    await expect(tenant).toHaveURL(/\/maintenance\/new$/);
    await expect(tenant.getByText('The request is linked to your current room automatically.')).toBeVisible();
    await expect(field(tenant, 'Property')).toHaveCount(0);
    await field(tenant, 'Category').selectOption({ label: 'Plumbing' });
    await field(tenant, 'Priority').selectOption({ label: 'High' });
    await field(tenant, 'Title').fill(title);
    await field(tenant, 'Description').fill('The bathroom tap keeps dripping, even when fully closed.');
    await tenant.getByRole('button', { name: 'Raise request' }).click();

    await expect(toast(tenant, 'Request raised')).toBeVisible();
    await expect(tenant.getByRole('heading', { level: 1, name: title })).toBeVisible();
    await expect(pageSubtitle(tenant)).toContainText('Open');
    path = `/maintenance/${idFromUrl(tenant, '/maintenance')}`;
    // a tenant can cancel an OPEN request or wait; nothing else
    await expect(tenant.getByRole('button', { name: 'Cancel request' })).toBeVisible();
    await expect(tenant.getByRole('button', { name: 'Assign' })).toHaveCount(0);
  });

  await test.step('manager assigns it to staff', async () => {
    const manager = await openAs('manager');
    await manager.goto(path);
    await expect(manager.getByRole('heading', { level: 1, name: title })).toBeVisible();
    await expect(manager.getByText('Unassigned')).toBeVisible();
    await manager.getByRole('button', { name: 'Assign', exact: true }).click();
    const dialog = manager.getByRole('dialog', { name: 'Assign ticket' });
    await field(dialog, 'Assignee').selectOption({ label: `${ACCOUNTS.staff.fullName} (Staff)` });
    await dialog.getByRole('button', { name: 'Assign', exact: true }).click();

    await expect(toast(manager, 'Ticket assigned')).toBeVisible();
    await expect(dialog).toBeHidden();
    await expect(pageSubtitle(manager)).toContainText('Assigned');
    await expect(manager.getByText(ACCOUNTS.staff.fullName).first()).toBeVisible();
  });

  await test.step('staff starts work and resolves with notes', async () => {
    const staff = await openAs('staff');
    await staff.goto(path);
    await expect(staff.getByRole('heading', { level: 1, name: title })).toBeVisible();
    await expect(staff.getByRole('button', { name: 'Assign' })).toHaveCount(0);

    await staff.getByRole('button', { name: 'Start work' }).click();
    let dialog = staff.getByRole('dialog', { name: 'Start work' });
    await dialog.getByRole('button', { name: 'Confirm' }).click();
    await expect(toast(staff, 'Status changed to In progress')).toBeVisible();
    await expect(pageSubtitle(staff)).toContainText('In progress');

    await staff.getByRole('button', { name: 'Mark resolved' }).click();
    dialog = staff.getByRole('dialog', { name: 'Mark resolved' });
    await dialog.getByRole('button', { name: 'Confirm' }).click();
    await expect(dialog.getByText('Resolution notes are required')).toBeVisible();
    await field(dialog, 'Resolution notes').fill(notes);
    await dialog.getByRole('button', { name: 'Confirm' }).click();

    await expect(toast(staff, 'Status changed to Resolved')).toBeVisible();
    await expect(pageSubtitle(staff)).toContainText('Resolved');
    await expect(staff.getByText(notes).first()).toBeVisible();
    await expect(staff.getByRole('button', { name: 'Mark resolved' })).toHaveCount(0);
  });

  await test.step('tenant closes it and the timeline shows every step', async () => {
    await tenant.reload();
    await expect(pageSubtitle(tenant)).toContainText('Resolved');
    await expect(tenant.getByRole('button', { name: 'Reopen' })).toBeVisible();
    await tenant.getByRole('button', { name: 'Close', exact: true }).click();
    const dialog = tenant.getByRole('dialog', { name: 'Close' });
    await dialog.getByRole('button', { name: 'Confirm' }).click();
    await expect(toast(tenant, 'Status changed to Closed')).toBeVisible();
    await expect(pageSubtitle(tenant)).toContainText('Closed');
    await expect(tenant.getByRole('button', { name: 'Close', exact: true })).toHaveCount(0);

    await expect(historyList(tenant).getByRole('listitem')).toHaveText([
      /^Open/,
      /^Open → Assigned/,
      /^Assigned → In progress/,
      new RegExp(`^In progress → Resolved${notes.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`),
      /^Resolved → Closed/,
    ]);
  });
});
