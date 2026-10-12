import { ACCOUNTS } from './support/env';
import { expect, test } from './support/fixtures';

/** Operator-only screens (router.tsx role gates). A user without the role is sent to their own home page. */
const OPERATOR_PAGES = ['/users', '/audit', '/dashboard', '/beds', '/tenants', '/tenants/new', '/invoices', '/payments', '/payments/new', '/bookings', '/properties', '/work'];

test.describe('access control in the UI', () => {
  test('a tenant cannot reach admin or operator pages', async ({ openAs, apiAs }) => {
    const page = await openAs('tenant');
    for (const path of OPERATOR_PAGES) {
      await page.goto(path);
      await expect(page, `${path} must redirect a tenant home`).toHaveURL('/me');
      await expect(page.getByRole('heading', { level: 1, name: ACCOUNTS.tenant.homeHeading })).toBeVisible();
    }
    // and the menu never offers them
    await expect(page.getByRole('link', { name: 'Users', exact: true })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Billing', exact: true })).toHaveCount(0);

    // the backend is the authority: the API refuses as well
    const api = await apiAs('tenant');
    expect((await api.get('/api/v1/users')).status()).toBe(403);
  });

  test('staff cannot reach billing, bookings or admin pages', async ({ openAs }) => {
    const page = await openAs('staff');
    for (const path of ['/users', '/audit', '/dashboard', '/invoices', '/payments', '/bookings', '/tenants/new', '/me']) {
      await page.goto(path);
      await expect(page, `${path} must redirect staff home`).toHaveURL('/work');
    }
    // read-only access where allowed
    await page.goto('/beds');
    await expect(page.getByRole('heading', { level: 1, name: 'Bed board' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add room' })).toHaveCount(0);
  });

  test('a manager cannot reach user administration or the audit log', async ({ openAs }) => {
    const page = await openAs('manager');
    for (const path of ['/users', '/audit', '/properties/new', '/me']) {
      await page.goto(path);
      await expect(page, `${path} must redirect a manager home`).toHaveURL('/dashboard');
    }
  });

  test('anonymous visitors are sent to the login page', async ({ page }) => {
    await page.goto('/users');
    await expect(page).toHaveURL(/\/login\?next=%2Fusers$/);
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  });
});
