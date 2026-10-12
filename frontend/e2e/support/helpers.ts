import { expect, type Locator, type Page } from '@playwright/test';

const escapeRegExp = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Form control by its visible label. Required fields render "Label*" (the asterisk is aria-hidden), so the label
 * is matched exactly with an optional trailing asterisk - "Password" never matches the "Show password" button.
 */
export function field(scope: Page | Locator, label: string): Locator {
  return scope.getByLabel(new RegExp(`^${escapeRegExp(label)}\\s*\\*?$`));
}

/** Unique, human-readable suffix for records created in the shared demo database. */
export function uniqueSuffix(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}-${Math.random().toString(36).slice(2, 6)}`;
}

/** A phone number the backend accepts (10-15 digits) that is very unlikely to collide. */
export function uniquePhone(): string {
  return `9${String(Date.now()).slice(-9)}`;
}

/** Success / info toast (role=status) with the given title. */
export function toast(page: Page, title: string | RegExp): Locator {
  return page.getByRole('status').filter({ hasText: title });
}

/** Numeric id from the current URL, e.g. `/tenants/42` -> 42. */
export function idFromUrl(page: Page, prefix: string): number {
  const m = new URL(page.url()).pathname.match(new RegExp(`^${prefix}/(\\d+)$`));
  if (!m) throw new Error(`URL ${page.url()} does not match ${prefix}/:id`);
  return Number(m[1]);
}

/** The top bar (role=banner) that holds the notification bell. */
export function topBar(page: Page): Locator {
  return page.getByRole('banner');
}

/**
 * Asserts the page cannot be scrolled sideways. Waits until the page heading is shown and the network is quiet
 * (lists, charts and lookups rendered) so late content is measured too.
 */
export async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await page.waitForLoadState('networkidle');
  const m = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
    path: location.pathname,
    // the widest offenders, to make a failure actionable
    offenders: Array.from(document.querySelectorAll<HTMLElement>('body *'))
      .filter((el) => el.getBoundingClientRect().right > window.innerWidth + 1)
      .slice(0, 5)
      .map((el) => `${el.tagName.toLowerCase()}.${String(el.className).split(' ').slice(0, 3).join('.')}`),
  }));
  expect(m.scrollWidth, `horizontal overflow on ${m.path}: ${m.offenders.join(', ')}`).toBeLessThanOrEqual(m.innerWidth);
}

/** The line under the page title (status badges, numbers): PageHeader renders it right after the H1. */
export function pageSubtitle(page: Page): Locator {
  return page.getByRole('heading', { level: 1 }).locator('xpath=following-sibling::div[1]');
}
