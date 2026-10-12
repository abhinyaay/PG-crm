import fs from 'node:fs/promises';
import { expect, test as setup } from '@playwright/test';
import { ACCOUNTS, AUTH_DIR, DEMO_PASSWORD, ROLES, statePath } from './support/env';
import { field } from './support/helpers';

/**
 * Signs in ONCE per role through the real login form and saves the storageState (refresh token in localStorage).
 * Every later test reuses these sessions through the SessionBroker - no other test logs in except the
 * wrong-password check. 4 logins per run, well under the ~20/min limit on /auth/login + /auth/refresh.
 */
setup.beforeAll(async () => {
  await fs.mkdir(AUTH_DIR, { recursive: true });
});

for (const role of ROLES) {
  const account = ACCOUNTS[role];
  setup(`sign in as ${role} (${account.role})`, async ({ page }) => {
    await page.goto('/login');
    await field(page, 'Email').fill(account.email);
    await field(page, 'Password').fill(DEMO_PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();

    // generous: the first auth request after an idle period can be slow (see the e2e report: Redis reconnect)
    await expect(page).toHaveURL(account.home, { timeout: 30_000 });
    await expect(page.getByRole('heading', { level: 1, name: account.homeHeading })).toBeVisible();
    await page.context().storageState({ path: statePath(role) });
  });
}
