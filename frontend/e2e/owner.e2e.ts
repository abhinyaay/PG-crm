import { expect, test } from './support/fixtures';
import { field } from './support/helpers';

const KPI_LABELS = ['Occupancy', 'Available beds', 'Active tenants', 'Revenue this month', 'Outstanding rent', 'Overdue invoices', 'Open maintenance', 'Open complaints'];
const BED_NAME = /^Room .+, bed .+: (Available|Occupied|Reserved|Maintenance)$/;

test.describe('owner (property admin)', () => {
  test('dashboard renders the KPIs and the analytics section', async ({ openAs }) => {
    const page = await openAs('owner');
    await page.goto('/dashboard');
    await expect(page.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible();

    const kpis = page.getByRole('region', { name: 'Key figures' }).getByRole('link');
    await expect(kpis).toHaveCount(KPI_LABELS.length);
    for (const [i, label] of KPI_LABELS.entries()) {
      await expect(kpis.nth(i)).toContainText(label, { ignoreCase: true });
    }
    // values are real numbers from the API, not placeholders
    await expect(kpis.filter({ hasText: /occupancy/i })).toContainText(/\d+(\.\d+)?\s?%/);
    await expect(kpis.filter({ hasText: /occupancy/i })).toContainText(/\d+\/\d+ beds/);
    await expect(kpis.filter({ hasText: /revenue this month/i })).toContainText('₹');
    await expect(kpis.filter({ hasText: /open complaints/i })).toContainText(/\d+ escalated/);

    await expect(page.getByRole('heading', { name: 'Upcoming check-outs' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Recent payments' })).toBeVisible();
    await expect(page.getByRole('heading', { level: 2, name: 'Analytics' })).toBeVisible();

    // KPI cards are links into the matching list
    await kpis.filter({ hasText: /available beds/i }).click();
    await expect(page).toHaveURL(/\/beds/);
    await expect(page.getByRole('heading', { level: 1, name: 'Bed board' })).toBeVisible();
  });

  test('bed board shows every bed with its status and filters by status', async ({ openAs }) => {
    const page = await openAs('owner');
    await page.goto('/beds');
    await expect(page.getByRole('heading', { level: 1, name: 'Bed board' })).toBeVisible();
    await expect(page).toHaveURL(/propertyId=\d+/);

    const beds = page.getByRole('button', { name: BED_NAME });
    await expect(beds.first()).toBeVisible();
    const total = await beds.count();
    expect(total).toBeGreaterThan(0);

    // the accessible status and the rendered status agree for every bed
    const states = await beds.evaluateAll((els) => els.map((el) => ({ label: el.getAttribute('aria-label') ?? '', status: el.getAttribute('data-status') ?? '' })));
    for (const s of states) {
      expect(s.label.toLowerCase().endsWith(s.status.toLowerCase())).toBe(true);
    }

    // the legend counts add up to the beds on the board
    const legend = page.getByRole('list', { name: 'Bed status legend' }).getByRole('listitem');
    await expect(legend).toHaveText([/^Available\s*· \d+$/, /^Occupied\s*· \d+$/, /^Reserved\s*· \d+$/, /^Maintenance\s*· \d+$/]);
    const counts = (await legend.allTextContents()).map((t) => Number(t.match(/· (\d+)/)?.[1] ?? 0));
    expect(counts.reduce((a, b) => a + b, 0)).toBe(total);

    // filter: every room left on the board has at least one bed in that state
    const occupied = counts[1] ?? 0;
    test.skip(occupied === 0, 'no occupied beds in this property');
    await field(page, 'Bed status').selectOption({ label: 'Occupied' });
    await expect(page).toHaveURL(/status=OCCUPIED/);
    const rooms = page.getByRole('list', { name: /^Beds in room / });
    await expect(rooms.first()).toBeVisible();
    for (const room of await rooms.all()) {
      await expect(room.getByRole('button', { name: /: Occupied$/ }).first()).toBeVisible();
    }
    await expect(page.getByRole('button', { name: /: Occupied$/ })).toHaveCount(occupied);
  });
});
