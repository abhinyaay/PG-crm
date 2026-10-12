import type { RoleName } from './support/env';
import { expect, test } from './support/fixtures';
import { expectNoHorizontalOverflow } from './support/helpers';

/** Phone-only behaviour (390x844). The rest of the suite also runs on the mobile project. */
test.describe('mobile layout', () => {
  test.skip(({ isMobile }) => !isMobile, 'phone layout only');

  test('bottom tab bar navigates and the "More" sheet reaches the remaining pages', async ({ openAs }) => {
    const page = await openAs('manager');
    await page.goto('/dashboard');
    const tabBar = page.getByRole('navigation', { name: 'Primary' });
    await expect(tabBar).toBeVisible();
    await expect(page.getByRole('complementary', { name: 'Main navigation' })).toBeHidden();

    // the tab bar stays pinned to the bottom of the viewport
    const box = (await tabBar.boundingBox())!;
    expect(Math.round(box.y + box.height)).toBe(page.viewportSize()!.height);
    expect(Math.round(box.width)).toBe(page.viewportSize()!.width);

    const tabs: [string, RegExp, string][] = [
      ['Beds', /\/beds/, 'Bed board'],
      ['Tenants', /\/tenants$/, 'Tenants'],
      ['Billing', /\/invoices$/, 'Invoices'],
      ['Dashboard', /\/dashboard$/, 'Dashboard'],
    ];
    for (const [tab, url, heading] of tabs) {
      const link = tabBar.getByRole('link', { name: tab, exact: true });
      await link.click();
      await expect(page).toHaveURL(url);
      await expect(page.getByRole('heading', { level: 1 })).toContainText(heading);
      await expect(link).toHaveAttribute('aria-current', 'page');
    }

    const more = tabBar.getByRole('button', { name: 'More' });
    await more.click();
    await expect(more).toHaveAttribute('aria-expanded', 'true');
    const sheet = page.getByRole('dialog', { name: 'More navigation' });
    await sheet.getByRole('link', { name: 'Complaints' }).click();
    await expect(page).toHaveURL(/\/complaints$/);
    await expect(sheet).toBeHidden();
    await expect(page.getByRole('heading', { level: 1, name: 'Complaints' })).toBeVisible();

    // Escape closes the sheet too
    await more.click();
    await expect(sheet).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(sheet).toBeHidden();
  });

  test('tenant tab bar works', async ({ openAs }) => {
    const page = await openAs('tenant');
    await page.goto('/me');
    const tabBar = page.getByRole('navigation', { name: 'Primary' });
    await tabBar.getByRole('link', { name: 'Bills', exact: true }).click();
    await expect(page).toHaveURL(/\/me\/bills/);
    await tabBar.getByRole('link', { name: 'Repairs', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'My maintenance requests' })).toBeVisible();
    await tabBar.getByRole('link', { name: 'Home', exact: true }).click();
    await expect(page).toHaveURL(/\/me$/);
  });

  const KEY_PAGES: Record<RoleName, string[]> = {
    owner: ['/users', '/audit', '/properties'],
    manager: ['/dashboard', '/beds', '/tenants', '/invoices', '/payments', '/payments/new', '/bookings', '/maintenance', '/complaints', '/properties', '/notifications', '/tenants/new', '/account'],
    staff: ['/work', '/maintenance', '/complaints', '/beds'],
    tenant: ['/me', '/me/bills', '/maintenance', '/maintenance/new', '/complaints', '/complaints/new', '/notifications', '/me/profile'],
  };

  for (const [role, paths] of Object.entries(KEY_PAGES) as [RoleName, string[]][]) {
    test(`no horizontal overflow on ${role} pages`, async ({ openAs }) => {
      const page = await openAs(role);
      for (const path of paths) {
        await page.goto(path);
        await expectNoHorizontalOverflow(page);
      }
    });
  }

  test('no horizontal overflow on detail pages', async ({ openAs }) => {
    const page = await openAs('manager');
    // open the first tenant, maintenance ticket and complaint from their lists
    for (const [list, detail] of [
      ['/tenants', /\/tenants\/\d+$/],
      ['/maintenance', /\/maintenance\/\d+$/],
      ['/complaints', /\/complaints\/\d+$/],
      ['/invoices', /\/invoices\/\d+$/],
    ] as const) {
      await page.goto(list);
      // phone lists render each row as a card with role=link; click its padding so an inner link is not hit
      const firstCard = page.getByRole('main').getByRole('listitem').locator('> [role="link"]').first();
      await firstCard.click({ position: { x: 6, y: 6 } });
      await expect(page).toHaveURL(detail);
      await expectNoHorizontalOverflow(page);
    }
  });
});
