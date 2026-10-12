import { expect, test } from './support/fixtures';
import { field, idFromUrl, pageSubtitle, toast, uniqueSuffix } from './support/helpers';

test('tenant files a complaint; manager replies publicly and adds an internal note the tenant never sees', async ({ openAs }) => {
  const suffix = uniqueSuffix();
  const subject = `E2E late-night noise ${suffix}`;
  const reply = `We have spoken to the residents of the next room (${suffix}).`;
  const internalNote = `Internal: check the CCTV for room 104 before replying (${suffix}).`;
  let path = '';

  const tenant = await openAs('tenant');
  await test.step('tenant creates the complaint', async () => {
    await tenant.goto('/complaints');
    await expect(tenant.getByRole('heading', { level: 1, name: 'My complaints' })).toBeVisible();
    await tenant.getByRole('link', { name: 'New complaint' }).click();
    await expect(field(tenant, 'Property')).toHaveCount(0);
    await field(tenant, 'Category').selectOption({ label: 'Noise' });
    await field(tenant, 'Priority').selectOption({ label: 'Medium' });
    await field(tenant, 'Subject').fill(subject);
    await field(tenant, 'Description').fill('Loud music after midnight on weekdays.');
    await tenant.getByRole('button', { name: 'Submit complaint' }).click();

    await expect(toast(tenant, 'Complaint registered')).toBeVisible();
    await expect(tenant.getByRole('heading', { level: 1, name: subject })).toBeVisible();
    await expect(pageSubtitle(tenant)).toContainText('Open');
    path = `/complaints/${idFromUrl(tenant, '/complaints')}`;
    await expect(tenant.getByText('No comments yet')).toBeVisible();
    // residents are never offered the internal-note toggle
    await expect(tenant.getByRole('checkbox', { name: /Internal note/ })).toHaveCount(0);
  });

  await test.step('manager posts a public reply and an internal note', async () => {
    const manager = await openAs('manager');
    await manager.goto(path);
    await expect(manager.getByRole('heading', { level: 1, name: subject })).toBeVisible();
    const comment = field(manager, 'Add a comment');
    const post = manager.getByRole('button', { name: 'Post' });
    const internal = manager.getByRole('checkbox', { name: /Internal note/ });
    const comments = manager.getByRole('listitem').filter({ hasText: suffix });

    await expect(post).toBeDisabled();
    await comment.fill(reply);
    await post.click();
    await expect(comments.filter({ hasText: reply })).toBeVisible();
    await expect(comment).toHaveValue('');
    await expect(internal).not.toBeChecked();

    await comment.fill(internalNote);
    await internal.check();
    await post.click();
    const note = comments.filter({ hasText: internalNote });
    await expect(note).toBeVisible();
    await expect(note).toContainText('Internal note');
    await expect(comments.filter({ hasText: reply })).not.toContainText('Internal note');
    await expect(manager.getByRole('tab', { name: /Comments/ })).toContainText('2');
  });

  await test.step('tenant sees the reply but not the internal note', async () => {
    await tenant.reload();
    await expect(tenant.getByRole('heading', { level: 1, name: subject })).toBeVisible();
    await expect(tenant.getByText(reply)).toBeVisible();
    await expect(tenant.getByText(internalNote)).toHaveCount(0);
    await expect(tenant.getByText('Internal note')).toHaveCount(0);
    await expect(tenant.getByRole('tab', { name: /Comments/ })).toContainText('1');

    // the timeline does not leak it either
    await tenant.getByRole('tab', { name: 'Timeline' }).click();
    await expect(tenant.getByRole('listitem').first()).toBeVisible();
    await expect(tenant.getByText(internalNote)).toHaveCount(0);
  });
});
