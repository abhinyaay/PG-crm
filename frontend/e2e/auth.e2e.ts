import { ACCOUNTS, ROLES, type RoleName } from './support/env';
import { expect, test } from './support/fixtures';
import { field } from './support/helpers';

/** Navigation entries per role, in order (frontend/src/components/layout/nav.ts). */
const SIDEBAR: Record<RoleName, string[]> = {
  owner: ['Dashboard', 'Bed board', 'Tenants', 'Billing', 'Payments', 'Bookings', 'Maintenance', 'Complaints', 'Properties', 'Notifications', 'Users', 'Audit log'],
  manager: ['Dashboard', 'Bed board', 'Tenants', 'Billing', 'Payments', 'Bookings', 'Maintenance', 'Complaints', 'Properties', 'Notifications'],
  staff: ['Work queue', 'Bed board', 'Tenants', 'Maintenance', 'Complaints', 'Properties', 'Notifications'],
  tenant: ['Home', 'Bills', 'Maintenance', 'Complaints', 'Notifications', 'My profile'],
};

/** Bottom tab bar on phones (4 tabs + "More") and the entries of the "More" sheet. */
const BOTTOM_TABS: Record<RoleName, { tabs: string[]; more: string[] }> = {
  owner: { tabs: ['Dashboard', 'Beds', 'Tenants', 'Billing'], more: ['Payments', 'Bookings', 'Maintenance', 'Complaints', 'Properties', 'Notifications', 'Users', 'Audit log'] },
  manager: { tabs: ['Dashboard', 'Beds', 'Tenants', 'Billing'], more: ['Payments', 'Bookings', 'Maintenance', 'Complaints', 'Properties', 'Notifications'] },
  staff: { tabs: ['Queue', 'Repairs', 'Complaints', 'Beds'], more: ['Tenants', 'Properties', 'Notifications'] },
  tenant: { tabs: ['Home', 'Bills', 'Repairs', 'Complaints'], more: ['Notifications', 'My profile'] },
};

test.describe('sign-in form', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  });

  test('validates required fields and the email format without calling the API', async ({ page }) => {
    let loginCalls = 0;
    page.on('request', (r) => {
      if (r.url().includes('/api/v1/auth/login')) loginCalls++;
    });
    const email = field(page, 'Email');
    const password = field(page, 'Password');

    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByText('Email is required')).toBeVisible();
    await expect(page.getByText('Password is required')).toBeVisible();
    await expect(email).toHaveAttribute('aria-invalid', 'true');
    await expect(password).toHaveAttribute('aria-invalid', 'true');

    await email.fill('not-an-email');
    await password.fill('whatever1');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByText('Enter a valid email address')).toBeVisible();
    await expect(page.getByText('Password is required')).toBeHidden();

    // the password visibility toggle
    await page.getByRole('button', { name: 'Show password' }).click();
    await expect(password).toHaveAttribute('type', 'text');
    await page.getByRole('button', { name: 'Hide password' }).click();
    await expect(password).toHaveAttribute('type', 'password');

    expect(loginCalls).toBe(0);
    await expect(page).toHaveURL(/\/login$/);
  });

  test('shows an error for a wrong password and stays on the login page', async ({ page }) => {
    await field(page, 'Email').fill(ACCOUNTS.manager.email);
    await field(page, 'Password').fill('Wrong-password-1');
    const response = page.waitForResponse((r) => r.url().endsWith('/api/v1/auth/login'));
    await page.getByRole('button', { name: 'Sign in' }).click();
    expect((await response).status()).toBe(401);

    await expect(page.getByRole('alert')).toContainText('Incorrect email or password.');
    await expect(page).toHaveURL(/\/login$/);
    expect(await page.evaluate(() => localStorage.getItem('pgms.refreshToken'))).toBeNull();
  });

  test('an anonymous deep link is sent to the login page and remembers where to go', async ({ page }) => {
    await page.goto('/tenants?status=ACTIVE');
    await expect(page).toHaveURL(/\/login\?next=%2Ftenants%3Fstatus%3DACTIVE$/);
  });
});

test.describe('role-based landing page and navigation', () => {
  for (const role of ROLES) {
    const account = ACCOUNTS[role];

    test(`${role} lands on ${account.home} with the ${account.role} menu`, async ({ openAs, isMobile }) => {
      const page = await openAs(role);
      await page.goto('/');
      await expect(page).toHaveURL(account.home);
      await expect(page.getByRole('heading', { level: 1, name: account.homeHeading })).toBeVisible();

      if (!isMobile) {
        const sidebar = page.getByRole('complementary', { name: 'Main navigation' });
        await expect(sidebar.getByRole('navigation').getByRole('link')).toHaveText(SIDEBAR[role]);
        await expect(sidebar).toContainText(account.fullName);
        await expect(page.getByRole('navigation', { name: 'Primary' })).toBeHidden();
      } else {
        await expect(page.getByRole('complementary', { name: 'Main navigation' })).toBeHidden();
        const tabBar = page.getByRole('navigation', { name: 'Primary' });
        await expect(tabBar.getByRole('link')).toHaveText(BOTTOM_TABS[role].tabs);
        await tabBar.getByRole('button', { name: 'More' }).click();
        const sheet = page.getByRole('dialog', { name: 'More navigation' });
        await expect(sheet.getByRole('navigation', { name: 'More' }).getByRole('link')).toHaveText([...BOTTOM_TABS[role].more, 'Account & security']);
        await sheet.getByRole('button', { name: 'Close menu' }).click();
        await expect(sheet).toBeHidden();
      }
    });
  }
});
